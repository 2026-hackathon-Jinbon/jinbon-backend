package com.jinbon.domain.video.service;

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
 * DCT 기반 지각해시(pHash) 서비스.
 *
 * 영상의 동일한 상대 시점에서 프레임을 추출하고,
 * 각 프레임의 64비트 pHash를 계산한다.
 * 재인코딩/리사이즈/압축에도 유사한 해시를 생성하여
 * 해밍 거리 기반 유사도 비교가 가능하다.
 */
@Service
public class PerceptualHashService {

    /** 비교할 상대 시점의 프레임 수 */
    private static final int MAX_FRAMES = 16;

    /** DCT 계산용 이미지 크기 */
    private static final int DCT_SIZE = 32;

    /** 해시 추출용 저주파 영역 크기 */
    private static final int HASH_SIZE = 8;

    /** 프레임 유사 판정 해밍 거리 임계값 */
    public static final int SIMILARITY_THRESHOLD = 10;

    /**
     * 영상 파일에서 지각해시 핑거프린트를 생성한다.
     * 16개 상대 시점의 프레임을 추출하고 각 프레임의 pHash를 계산한다.
     *
     * @return v2|영상 길이(마이크로초)|프레임별 pHash 목록
     */
    public String generateFingerprint(MultipartFile file) throws IOException {
        File tempFile = File.createTempFile("jinbon-phash-", ".tmp");
        try {
            file.transferTo(tempFile);
            return extractFingerprint(tempFile);
        } finally {
            Files.deleteIfExists(tempFile.toPath());
        }
    }

    /**
     * 파일 경로에서 지각해시 핑거프린트를 생성한다.
     * URL 다운로드 등 이미 파일이 존재하는 경우 사용한다.
     */
    public String generateFingerprint(Path videoFile) throws IOException {
        return extractFingerprint(videoFile.toFile());
    }

    /** 같은 상대 시점의 거리와 일치율을 비교한다. 길이는 5% 이내만 허용한다. */
    public Comparison compare(String first, String second) {
        Fingerprint a = parse(first);
        Fingerprint b = parse(second);
        if (a.hashes().isEmpty() || b.hashes().isEmpty()) {
            return new Comparison(64, 64, 0, false, false);
        }
        int count = Math.max(a.hashes().size(), b.hashes().size());
        int total = 0, maximum = 0, matched = 0;
        for (int i = 0; i < count; i++) {
            int ai = Math.min((int) ((i + 0.5) * a.hashes().size() / count), a.hashes().size() - 1);
            int bi = Math.min((int) ((i + 0.5) * b.hashes().size() / count), b.hashes().size() - 1);
            int distance = Long.bitCount(a.hashes().get(ai) ^ b.hashes().get(bi));
            total += distance;
            maximum = Math.max(maximum, distance);
            if (distance <= SIMILARITY_THRESHOLD) matched++;
        }
        boolean durationCompatible = a.durationMicros() > 0 && b.durationMicros() > 0
                && (double) Math.abs(a.durationMicros() - b.durationMicros())
                / Math.max(a.durationMicros(), b.durationMicros()) <= 0.05;
        // 순서 없는 대응은 부분 유사 후보를 찾는 용도로만 사용한다.
        boolean partial = Math.max(coverage(a.hashes(), b.hashes()),
                coverage(b.hashes(), a.hashes())) >= 0.6;
        return new Comparison((double) total / count, maximum, (double) matched / count,
                durationCompatible, partial);
    }

    public record Comparison(double meanDistance, int maxDistance, double matchedRatio,
                             boolean durationCompatible, boolean partial) {
        public boolean similar() {
            return durationCompatible && meanDistance <= SIMILARITY_THRESHOLD
                    && matchedRatio >= 0.9 && maxDistance <= 16;
        }
    }

    private double coverage(List<Long> source, List<Long> target) {
        long matches = source.stream().filter(a -> target.stream()
                .anyMatch(b -> Long.bitCount(a ^ b) <= SIMILARITY_THRESHOLD)).count();
        return (double) matches / source.size();
    }

    private record Fingerprint(long durationMicros, List<Long> hashes) {}

    private Fingerprint parse(String value) {
        if (value != null && value.startsWith("v2|")) {
            String[] parts = value.split("\\|", 3);
            return new Fingerprint(Long.parseLong(parts[1]), stringToFrameHashes(parts[2]));
        }
        // 기존 해시는 온체인 커밋에 포함되므로 덮어쓰지 않는다.
        return new Fingerprint(0, stringToFrameHashes(value));
    }

    /** 길이와 동일한 상대 시점의 16개 프레임을 기존 TEXT 컬럼에 함께 저장한다. */
    private String extractFingerprint(File videoFile) throws IOException {
        List<Long> hashes = new ArrayList<>();
        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(videoFile);
             Java2DFrameConverter converter = new Java2DFrameConverter()) {
            grabber.start();
            long durationMicros = grabber.getLengthInTime();
            if (durationMicros <= 0) throw new IOException("Video duration is unavailable");
            for (int i = 0; i < MAX_FRAMES; i++) {
                grabber.setTimestamp((long) ((i + 0.5) * durationMicros / MAX_FRAMES));
                Frame frame = grabber.grabImage();
                BufferedImage image = frame == null ? null : converter.convert(frame);
                // 추출 실패를 건너뛰면 상대 시점이 어긋나므로 검증을 중단한다.
                if (image == null) throw new IOException("Could not extract comparison frame");
                hashes.add(computePHash(image));
            }
            return "v2|" + durationMicros + "|" + framHashesToString(hashes);
        } catch (Exception e) {
            throw new IOException("Failed to extract frames from video", e);
        }
    }

    /**
     * DCT 기반 지각해시(pHash)를 계산한다.
     *
     * 1. 32x32 그레이스케일로 리사이즈
     * 2. 32x32 DCT 적용
     * 3. 좌상단 8x8 저주파 영역 추출
     * 4. 평균값 기준으로 64비트 해시 생성
     */
    private long computePHash(BufferedImage image) {
        // 1. 32x32 그레이스케일로 리사이즈
        double[][] gray = toGrayscaleResized(image, DCT_SIZE);

        // 2. DCT 적용
        double[][] dct = applyDCT(gray);

        // 3. 8x8 저주파 영역의 평균 계산 (DC 성분 [0][0] 제외)
        double sum = 0;
        for (int i = 0; i < HASH_SIZE; i++) {
            for (int j = 0; j < HASH_SIZE; j++) {
                if (i != 0 || j != 0) {
                    sum += dct[i][j];
                }
            }
        }
        double mean = sum / (HASH_SIZE * HASH_SIZE - 1);

        // 4. 평균보다 크면 1, 작으면 0 → 64비트 해시
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

    /**
     * 이미지를 지정 크기의 그레이스케일 2D 배열로 변환한다.
     */
    private double[][] toGrayscaleResized(BufferedImage image, int size) {
        BufferedImage resized = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = resized.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
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

    /**
     * 2D DCT (이산 코사인 변환)를 적용한다.
     */
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

    /**
     * 프레임 해시 목록을 쉼표 구분 hex 문자열로 변환한다.
     */
    private String framHashesToString(List<Long> hashes) {
        return String.join(",", hashes.stream()
                .map(h -> String.format("%016x", h))
                .toList());
    }

    /**
     * 쉼표 구분 hex 문자열을 프레임 해시 목록으로 변환한다.
     */
    private List<Long> stringToFrameHashes(String fingerprint) {
        if (fingerprint == null || fingerprint.isBlank()) {
            return List.of();
        }
        List<Long> hashes = new ArrayList<>();
        for (String hex : fingerprint.split(",")) {
            hashes.add(Long.parseUnsignedLong(hex.trim(), 16));
        }
        return hashes;
    }
}
