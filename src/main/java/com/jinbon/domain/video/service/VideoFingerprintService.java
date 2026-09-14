package com.jinbon.domain.video.service;

import com.jinbon.domain.video.dto.GapRange;
import com.jinbon.domain.video.dto.SegmentMatchResult;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 세그먼트 기반 영상 지문 서비스.
 *
 * <p>영상을 고정 시간 간격(기본 1초)으로 나누어 각 구간의 대표 프레임에서
 * DCT pHash를 추출하고, 시간 순서대로 저장한다.</p>
 *
 * <h3>기존 PerceptualHashService와의 역할 분리</h3>
 * <ul>
 *   <li>{@link PerceptualHashService} — 16개 상대 시점 pHash로 <b>원본 후보를 빠르게 검색</b></li>
 *   <li>{@code VideoFingerprintService} — 고정 간격 세그먼트 pHash로 <b>후보의 구간별 정밀 비교</b></li>
 * </ul>
 *
 * <h3>세그먼트 지문 포맷</h3>
 * <pre>seg-v1|{durationMicros}|{intervalMs}|{hash0},{hash1},{hash2},...</pre>
 *
 * <h3>비교 알고리즘</h3>
 * <ol>
 *   <li>슬라이딩 윈도우로 최적 오프셋 검색 (Hamming 거리 기반)</li>
 *   <li>최적 오프셋에서 세그먼트별 일치 판정</li>
 *   <li>커버리지, 시간 순서, 불일치 구간 계산</li>
 * </ol>
 */
@Service
public class VideoFingerprintService {

    /** 세그먼트 추출 간격 (밀리초) */
    static final int DEFAULT_INTERVAL_MS = 1000;

    /** 세그먼트 지문 포맷 접두어 */
    private static final String FORMAT_PREFIX = "seg-v1";

    /** DCT 계산용 이미지 크기 */
    private static final int DCT_SIZE = 32;

    /** 해시 추출용 저주파 영역 크기 */
    private static final int HASH_SIZE = 8;

    /** 세그먼트 일치 판정 해밍 거리 임계값 */
    static final int MATCH_THRESHOLD = 10;

    // ── 지문 생성 ─────────────────────────────────────────

    /**
     * 업로드된 영상 파일에서 세그먼트 지문을 생성한다.
     */
    public String generate(MultipartFile file) throws IOException {
        File temp = File.createTempFile("jinbon-seg-", ".tmp");
        try {
            file.transferTo(temp);
            return extractSegments(temp);
        } finally {
            Files.deleteIfExists(temp.toPath());
        }
    }

    /**
     * 파일 경로에서 세그먼트 지문을 생성한다.
     * URL 다운로드 등 이미 파일이 존재하는 경우 사용한다.
     */
    public String generate(Path videoFile) throws IOException {
        return extractSegments(videoFile.toFile());
    }

    // ── 지문 비교 ─────────────────────────────────────────

    /**
     * 제출 영상(query)의 세그먼트 지문과 원본(reference)의 세그먼트 지문을 비교한다.
     *
     * <ol>
     *   <li>슬라이딩 윈도우로 가장 많은 세그먼트가 일치하는 오프셋을 찾는다.</li>
     *   <li>최적 오프셋에서 커버리지와 순서 보존 여부를 계산한다.</li>
     *   <li>불일치하는 연속 구간을 {@link GapRange}로 묶는다.</li>
     * </ol>
     *
     * @param queryFingerprint     제출 영상의 세그먼트 지문
     * @param referenceFingerprint 원본의 세그먼트 지문
     * @return 비교 결과 (커버리지, 오프셋, 순서, 불일치 구간)
     */
    public SegmentMatchResult compare(String queryFingerprint, String referenceFingerprint) {
        SegmentFingerprint query = parse(queryFingerprint);
        SegmentFingerprint ref = parse(referenceFingerprint);
        if (query == null || ref == null
                || query.hashes().isEmpty() || ref.hashes().isEmpty()) {
            return SegmentMatchResult.unavailable();
        }

        long intervalMs = query.intervalMs();
        List<Long> q = query.hashes();
        List<Long> r = ref.hashes();
        int m = q.size();
        int n = r.size();

        // 1단계: 슬라이딩 윈도우로 최적 오프셋 검색
        int bestOffset = 0;
        int bestMatchCount = -1;
        for (int offset = -(m - 1); offset < n; offset++) {
            int matchCount = 0;
            for (int i = 0; i < m; i++) {
                int ri = i + offset;
                if (ri < 0 || ri >= n) continue;
                if (Long.bitCount(q.get(i) ^ r.get(ri)) <= MATCH_THRESHOLD) {
                    matchCount++;
                }
            }
            if (matchCount > bestMatchCount) {
                bestMatchCount = matchCount;
                bestOffset = offset;
            }
        }

        // 2단계: 최적 오프셋에서 세그먼트별 일치 판정
        boolean[] matched = new boolean[m];
        int matchedCount = 0;
        int lastMatchedRefIndex = -1;
        boolean orderPreserved = true;

        for (int i = 0; i < m; i++) {
            int ri = i + bestOffset;
            if (ri < 0 || ri >= n) continue;
            if (Long.bitCount(q.get(i) ^ r.get(ri)) <= MATCH_THRESHOLD) {
                matched[i] = true;
                matchedCount++;
                // 순서 검증: 일치한 원본 인덱스가 단조 증가하는지
                if (lastMatchedRefIndex >= 0 && ri <= lastMatchedRefIndex) {
                    orderPreserved = false;
                }
                lastMatchedRefIndex = ri;
            }
        }

        double coverage = m > 0 ? (double) matchedCount / m : 0;

        // 3단계: 원본 대응 구간 (시작·끝)
        long matchedStartMs = -1;
        long matchedEndMs = -1;
        for (int i = 0; i < m; i++) {
            if (!matched[i]) continue;
            long refTimeMs = (long) (i + bestOffset) * intervalMs;
            if (matchedStartMs < 0 || refTimeMs < matchedStartMs) {
                matchedStartMs = refTimeMs;
            }
            if (refTimeMs + intervalMs > matchedEndMs) {
                matchedEndMs = refTimeMs + intervalMs;
            }
        }
        if (matchedStartMs < 0) {
            matchedStartMs = 0;
            matchedEndMs = 0;
        }

        // 4단계: 불일치 구간 집계
        List<GapRange> gaps = buildGaps(matched, intervalMs);

        return new SegmentMatchResult(
                coverage, orderPreserved,
                (long) bestOffset * intervalMs,
                matchedStartMs, matchedEndMs,
                matchedCount, m, n,
                gaps
        );
    }

    // ── 프레임 추출 ───────────────────────────────────────

    /**
     * 영상에서 고정 간격으로 프레임을 추출하고 각 프레임의 pHash를 계산한다.
     *
     * 추출 시점: intervalMs/2, intervalMs*3/2, intervalMs*5/2, ...
     * (각 구간의 중앙 시점을 사용하여 경계 노이즈를 줄인다)
     */
    private String extractSegments(File videoFile) throws IOException {
        List<Long> hashes = new ArrayList<>();
        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(videoFile);
             Java2DFrameConverter converter = new Java2DFrameConverter()) {
            grabber.start();
            long durationMicros = grabber.getLengthInTime();
            if (durationMicros <= 0) {
                throw new IOException("Video duration is unavailable");
            }

            long intervalMicros = (long) DEFAULT_INTERVAL_MS * 1000;
            int segmentCount = (int) (durationMicros / intervalMicros);
            if (segmentCount == 0) segmentCount = 1;

            for (int i = 0; i < segmentCount; i++) {
                // 각 구간 중앙 시점
                long timestamp = i * intervalMicros + intervalMicros / 2;
                if (timestamp >= durationMicros) {
                    timestamp = durationMicros - 1;
                }
                grabber.setTimestamp(timestamp);
                Frame frame = grabber.grabImage();
                BufferedImage image = frame == null ? null : converter.convert(frame);
                if (image == null) {
                    // 세그먼트 추출 실패 시 0으로 채워 시간 정렬을 유지한다
                    hashes.add(0L);
                    continue;
                }
                hashes.add(computePHash(image));
            }

            return FORMAT_PREFIX + "|" + durationMicros + "|" + DEFAULT_INTERVAL_MS
                    + "|" + hashesToString(hashes);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Failed to extract segment fingerprints", e);
        }
    }

    // ── pHash 계산 (PerceptualHashService와 동일 알고리즘) ─

    private long computePHash(BufferedImage image) {
        double[][] gray = toGrayscaleResized(image, DCT_SIZE);
        double[][] dct = applyDCT(gray);

        double sum = 0;
        for (int i = 0; i < HASH_SIZE; i++) {
            for (int j = 0; j < HASH_SIZE; j++) {
                if (i != 0 || j != 0) {
                    sum += dct[i][j];
                }
            }
        }
        double mean = sum / (HASH_SIZE * HASH_SIZE - 1);

        long hash = 0L;
        for (int i = 0; i < HASH_SIZE; i++) {
            for (int j = 0; j < HASH_SIZE; j++) {
                if (dct[i][j] > mean) {
                    hash |= 1L << (i * HASH_SIZE + j);
                }
            }
        }
        return hash;
    }

    private double[][] toGrayscaleResized(BufferedImage image, int size) {
        BufferedImage resized = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = resized.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(image, 0, 0, size, size, null);
        g.dispose();

        double[][] gray = new double[size][size];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int rgb = resized.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g2 = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                gray[y][x] = 0.299 * r + 0.587 * g2 + 0.114 * b;
            }
        }
        return gray;
    }

    private double[][] applyDCT(double[][] input) {
        int n = input.length;
        double[][] output = new double[n][n];
        for (int u = 0; u < n; u++) {
            for (int v = 0; v < n; v++) {
                double sum = 0;
                for (int i = 0; i < n; i++) {
                    for (int j = 0; j < n; j++) {
                        sum += input[i][j]
                                * Math.cos((2 * i + 1) * u * Math.PI / (2 * n))
                                * Math.cos((2 * j + 1) * v * Math.PI / (2 * n));
                    }
                }
                double cu = (u == 0) ? 1.0 / Math.sqrt(2) : 1.0;
                double cv = (v == 0) ? 1.0 / Math.sqrt(2) : 1.0;
                output[u][v] = (2.0 / n) * cu * cv * sum;
            }
        }
        return output;
    }

    // ── 파싱·직렬화 ───────────────────────────────────────

    /** 세그먼트 지문 파싱 결과 */
    record SegmentFingerprint(long durationMicros, long intervalMs, List<Long> hashes) {}

    /**
     * 세그먼트 지문 문자열을 파싱한다.
     * 형식이 맞지 않으면 null을 반환한다.
     */
    SegmentFingerprint parse(String value) {
        if (value == null || !value.startsWith(FORMAT_PREFIX + "|")) {
            return null;
        }
        String[] parts = value.split("\\|", 4);
        if (parts.length < 4) return null;
        try {
            long durationMicros = Long.parseLong(parts[1]);
            long intervalMs = Long.parseLong(parts[2]);
            List<Long> hashes = parseHashes(parts[3]);
            return new SegmentFingerprint(durationMicros, intervalMs, hashes);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private List<Long> parseHashes(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        List<Long> hashes = new ArrayList<>();
        for (String hex : csv.split(",")) {
            hashes.add(Long.parseUnsignedLong(hex.trim(), 16));
        }
        return hashes;
    }

    private String hashesToString(List<Long> hashes) {
        return String.join(",", hashes.stream()
                .map(h -> String.format("%016x", h))
                .toList());
    }

    // ── 불일치 구간 계산 ──────────────────────────────────

    /**
     * 연속으로 불일치하는 세그먼트들을 GapRange로 묶는다.
     */
    private List<GapRange> buildGaps(boolean[] matched, long intervalMs) {
        List<GapRange> gaps = new ArrayList<>();
        int gapStart = -1;
        for (int i = 0; i < matched.length; i++) {
            if (!matched[i]) {
                if (gapStart < 0) gapStart = i;
            } else {
                if (gapStart >= 0) {
                    gaps.add(new GapRange(gapStart * intervalMs, i * intervalMs));
                    gapStart = -1;
                }
            }
        }
        if (gapStart >= 0) {
            gaps.add(new GapRange(gapStart * intervalMs, (long) matched.length * intervalMs));
        }
        return gaps;
    }
}
