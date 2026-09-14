package com.jinbon.domain.video.service;

import com.jinbon.domain.video.dto.GapRange;
import com.jinbon.domain.video.dto.SegmentMatchResult;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ShortBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 세그먼트 기반 음성 지문 서비스.
 *
 * <p>영상의 오디오 트랙을 고정 시간 간격(기본 1초)으로 나누어
 * 각 구간의 스펙트로그램에서 DCT pHash를 추출하고 시간 순서대로 저장한다.</p>
 *
 * <h3>역할</h3>
 * <ul>
 *   <li>등록 시 — 원본 음성 지문을 생성하여 DB에 저장</li>
 *   <li>검증 시 — 제출 영상의 음성 지문과 원본을 비교하여 음성 변조 여부를 판정</li>
 * </ul>
 *
 * <h3>음성 지문 포맷</h3>
 * <pre>aud-v1|{durationMicros}|{intervalMs}|{hash0},{hash1},{hash2},...</pre>
 *
 * <h3>비교 알고리즘</h3>
 * <p>{@link VideoFingerprintService}와 동일한 슬라이딩 윈도우 방식.
 * 해밍 거리 기반으로 최적 오프셋을 찾고 커버리지·순서·불일치 구간을 계산한다.</p>
 *
 * <h3>정규화</h3>
 * <p>FFmpeg 디코더가 mono 16kHz PCM으로 변환하도록 설정하여
 * 코덱·채널·샘플레이트 차이를 흡수한다.</p>
 */
@Service
public class AudioFingerprintService {

    /** 세그먼트 추출 간격 (밀리초) */
    static final int DEFAULT_INTERVAL_MS = 1000;

    /** 음성 지문 포맷 접두어 */
    private static final String FORMAT_PREFIX = "aud-v1";

    /** 정규화 샘플레이트 — 음성 신호 비교에 충분한 해상도 */
    private static final int SAMPLE_RATE = 16000;

    /** 스펙트로그램 크기 — DCT pHash 입력용 */
    private static final int SPECTROGRAM_SIZE = 32;

    /** 해시 추출용 저주파 영역 크기 */
    private static final int HASH_SIZE = 8;

    /** 세그먼트 일치 판정 해밍 거리 임계값 */
    static final int MATCH_THRESHOLD = 10;

    /** 무음 판정 RMS 임계값 (16-bit PCM 기준) */
    static final double SILENCE_RMS_THRESHOLD = 50.0;

    // ── 지문 생성 ─────────────────────────────────────────

    /**
     * 업로드된 영상 파일에서 음성 지문을 생성한다.
     *
     * @return 음성 지문 문자열, 오디오 트랙이 없으면 null
     */
    public String generate(MultipartFile file) throws IOException {
        File temp = File.createTempFile("jinbon-aud-", ".tmp");
        try {
            file.transferTo(temp);
            return extractAudioFingerprint(temp);
        } finally {
            Files.deleteIfExists(temp.toPath());
        }
    }

    /**
     * 파일 경로에서 음성 지문을 생성한다.
     * URL 다운로드 등 이미 파일이 존재하는 경우 사용한다.
     *
     * @return 음성 지문 문자열, 오디오 트랙이 없으면 null
     */
    public String generate(Path videoFile) throws IOException {
        return extractAudioFingerprint(videoFile.toFile());
    }

    // ── 지문 비교 ─────────────────────────────────────────

    /**
     * 제출 영상(query)의 음성 지문과 원본(reference)의 음성 지문을 비교한다.
     *
     * <p>{@link VideoFingerprintService#compare(String, String)}와 동일한 알고리즘으로
     * 슬라이딩 윈도우 최적 오프셋 → 커버리지·순서·갭 계산을 수행한다.
     * 추가로 무음 세그먼트 수를 계산하여 결과에 포함한다.</p>
     *
     * @param queryFingerprint     제출 영상의 음성 지문
     * @param referenceFingerprint 원본의 음성 지문
     * @return 비교 결과 (커버리지, 오프셋, 순서, 불일치 구간, 무음 수)
     */
    public SegmentMatchResult compare(String queryFingerprint, String referenceFingerprint) {
        AudioFingerprint query = parse(queryFingerprint);
        AudioFingerprint ref = parse(referenceFingerprint);
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
                if (lastMatchedRefIndex >= 0 && ri <= lastMatchedRefIndex) {
                    orderPreserved = false;
                }
                lastMatchedRefIndex = ri;
            }
        }

        double coverage = m > 0 ? (double) matchedCount / m : 0;

        // 3단계: 원본 대응 구간
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

        // 5단계: 무음 세그먼트 수
        int silentCount = 0;
        for (boolean silent : query.silentFlags()) {
            if (silent) silentCount++;
        }

        return new SegmentMatchResult(
                coverage, orderPreserved,
                (long) bestOffset * intervalMs,
                matchedStartMs, matchedEndMs,
                matchedCount, m, n,
                gaps, silentCount
        );
    }

    // ── 오디오 추출 및 지문 생성 ─────────────────────────

    /**
     * 영상에서 오디오 트랙을 추출하고 세그먼트별 스펙트로그램 pHash를 계산한다.
     *
     * <p>FFmpegFrameGrabber를 mono 16kHz로 설정하여 정규화한 뒤,
     * 1초 단위로 PCM 샘플을 수집하고 각 세그먼트의 스펙트로그램에서 pHash를 추출한다.</p>
     *
     * @return 음성 지문 문자열, 오디오 트랙이 없으면 null
     */
    private String extractAudioFingerprint(File videoFile) throws IOException {
        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(videoFile)) {
            // 오디오 정규화: mono, 16kHz
            grabber.setSampleRate(SAMPLE_RATE);
            grabber.setAudioChannels(1);
            grabber.start();

            // 오디오 트랙 없음
            if (grabber.getAudioChannels() == 0) {
                return null;
            }

            long durationMicros = grabber.getLengthInTime();
            if (durationMicros <= 0) {
                return null;
            }

            int samplesPerSegment = SAMPLE_RATE * DEFAULT_INTERVAL_MS / 1000;
            int segmentCount = (int) (durationMicros / ((long) DEFAULT_INTERVAL_MS * 1000));
            if (segmentCount == 0) segmentCount = 1;

            List<Long> hashes = new ArrayList<>();
            List<Boolean> silentFlags = new ArrayList<>();
            List<Short> sampleBuffer = new ArrayList<>();
            int currentSegment = 0;

            // 오디오 샘플을 순차적으로 읽어 세그먼트별로 누적
            Frame audioFrame;
            while ((audioFrame = grabber.grabSamples()) != null && currentSegment < segmentCount) {
                if (audioFrame.samples == null || audioFrame.samples.length == 0) continue;

                ShortBuffer samples = (ShortBuffer) audioFrame.samples[0];
                samples.rewind();
                while (samples.hasRemaining() && currentSegment < segmentCount) {
                    sampleBuffer.add(samples.get());

                    // 세그먼트 분량 채워지면 처리
                    if (sampleBuffer.size() >= samplesPerSegment) {
                        short[] segmentSamples = toShortArray(sampleBuffer, samplesPerSegment);
                        sampleBuffer.subList(0, samplesPerSegment).clear();

                        boolean silent = isSilent(segmentSamples);
                        silentFlags.add(silent);

                        if (silent) {
                            // 무음 세그먼트는 0으로 채워 시간 정렬 유지
                            hashes.add(0L);
                        } else {
                            double[][] spectrogram = buildSpectrogram(segmentSamples);
                            hashes.add(computeSpectrogramHash(spectrogram));
                        }
                        currentSegment++;
                    }
                }
            }

            // 마지막 불완전 세그먼트 처리
            if (currentSegment < segmentCount && !sampleBuffer.isEmpty()) {
                short[] segmentSamples = toShortArray(sampleBuffer, sampleBuffer.size());
                boolean silent = isSilent(segmentSamples);
                silentFlags.add(silent);
                if (silent) {
                    hashes.add(0L);
                } else {
                    double[][] spectrogram = buildSpectrogram(segmentSamples);
                    hashes.add(computeSpectrogramHash(spectrogram));
                }
                currentSegment++;
            }

            if (hashes.isEmpty()) {
                return null;
            }

            // 전체가 무음이면 지문을 생성하지 않는다
            if (silentFlags.stream().allMatch(s -> s)) {
                return null;
            }

            return FORMAT_PREFIX + "|" + durationMicros + "|" + DEFAULT_INTERVAL_MS
                    + "|" + hashesToString(hashes);

        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Failed to extract audio fingerprint", e);
        }
    }

    // ── 스펙트로그램 및 해시 계산 ────────────────────────

    /**
     * PCM 샘플에서 스펙트로그램을 생성한다.
     *
     * <p>STFT (Short-Time Fourier Transform):
     * 세그먼트를 겹치는 프레임으로 나누고,
     * 각 프레임에 Hann 윈도우를 적용한 뒤 FFT의 magnitude를 계산한다.
     * 결과를 SPECTROGRAM_SIZE × SPECTROGRAM_SIZE로 리사이즈한다.</p>
     */
    private double[][] buildSpectrogram(short[] samples) {
        int fftSize = 512;
        int hopSize = fftSize / 2;
        int numFrames = Math.max(1, (samples.length - fftSize) / hopSize + 1);
        int numBins = fftSize / 2;

        // STFT: 시간 × 주파수 magnitude
        double[][] rawSpectrogram = new double[numFrames][numBins];
        for (int f = 0; f < numFrames; f++) {
            int start = f * hopSize;
            double[] windowed = new double[fftSize];
            for (int i = 0; i < fftSize; i++) {
                int idx = start + i;
                double sample = idx < samples.length ? samples[idx] / 32768.0 : 0;
                // Hann 윈도우
                windowed[i] = sample * 0.5 * (1 - Math.cos(2 * Math.PI * i / (fftSize - 1)));
            }
            double[] magnitude = computeFFTMagnitude(windowed);
            System.arraycopy(magnitude, 0, rawSpectrogram[f], 0, numBins);
        }

        // SPECTROGRAM_SIZE × SPECTROGRAM_SIZE로 리사이즈
        return resizeSpectrogram(rawSpectrogram, numFrames, numBins);
    }

    /**
     * 실수 입력에 대해 FFT magnitude를 계산한다.
     * 주파수 대역의 하위 절반 (양의 주파수) magnitude를 반환한다.
     */
    private double[] computeFFTMagnitude(double[] input) {
        int n = input.length;
        double[] real = new double[n];
        double[] imag = new double[n];
        System.arraycopy(input, 0, real, 0, n);

        // Cooley-Tukey FFT (입력 크기는 2의 거듭제곱이어야 함)
        fft(real, imag);

        double[] magnitude = new double[n / 2];
        for (int i = 0; i < n / 2; i++) {
            magnitude[i] = Math.log1p(Math.sqrt(real[i] * real[i] + imag[i] * imag[i]));
        }
        return magnitude;
    }

    /**
     * in-place Cooley-Tukey FFT.
     */
    private void fft(double[] real, double[] imag) {
        int n = real.length;

        // 비트 반전 재배열
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            while ((j & bit) != 0) {
                j ^= bit;
                bit >>= 1;
            }
            j ^= bit;
            if (i < j) {
                double temp = real[i]; real[i] = real[j]; real[j] = temp;
                temp = imag[i]; imag[i] = imag[j]; imag[j] = temp;
            }
        }

        // 버터플라이 연산
        for (int len = 2; len <= n; len *= 2) {
            double angle = -2 * Math.PI / len;
            double wReal = Math.cos(angle);
            double wImag = Math.sin(angle);
            for (int i = 0; i < n; i += len) {
                double curReal = 1, curImag = 0;
                for (int j = 0; j < len / 2; j++) {
                    double uReal = real[i + j];
                    double uImag = imag[i + j];
                    double vReal = real[i + j + len / 2] * curReal - imag[i + j + len / 2] * curImag;
                    double vImag = real[i + j + len / 2] * curImag + imag[i + j + len / 2] * curReal;
                    real[i + j] = uReal + vReal;
                    imag[i + j] = uImag + vImag;
                    real[i + j + len / 2] = uReal - vReal;
                    imag[i + j + len / 2] = uImag - vImag;
                    double newCurReal = curReal * wReal - curImag * wImag;
                    curImag = curReal * wImag + curImag * wReal;
                    curReal = newCurReal;
                }
            }
        }
    }

    /**
     * 스펙트로그램을 목표 크기로 bilinear 리사이즈한다.
     */
    private double[][] resizeSpectrogram(double[][] raw, int rows, int cols) {
        double[][] resized = new double[SPECTROGRAM_SIZE][SPECTROGRAM_SIZE];
        for (int y = 0; y < SPECTROGRAM_SIZE; y++) {
            for (int x = 0; x < SPECTROGRAM_SIZE; x++) {
                double srcY = (double) y * (rows - 1) / (SPECTROGRAM_SIZE - 1);
                double srcX = (double) x * (cols - 1) / (SPECTROGRAM_SIZE - 1);
                int y0 = Math.min((int) srcY, rows - 1);
                int y1 = Math.min(y0 + 1, rows - 1);
                int x0 = Math.min((int) srcX, cols - 1);
                int x1 = Math.min(x0 + 1, cols - 1);
                double fy = srcY - y0;
                double fx = srcX - x0;
                resized[y][x] = raw[y0][x0] * (1 - fy) * (1 - fx)
                        + raw[y0][x1] * (1 - fy) * fx
                        + raw[y1][x0] * fy * (1 - fx)
                        + raw[y1][x1] * fy * fx;
            }
        }
        return resized;
    }

    /**
     * 스펙트로그램에서 DCT pHash를 계산한다.
     * 영상 pHash와 동일한 알고리즘: DCT → 8×8 저주파 → 평균 기준 64-bit 해시.
     */
    private long computeSpectrogramHash(double[][] spectrogram) {
        double[][] dct = applyDCT(spectrogram);

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

    // ── 무음 판정 ─────────────────────────────────────────

    /**
     * PCM 샘플의 RMS가 임계값 이하이면 무음으로 판정한다.
     */
    private boolean isSilent(short[] samples) {
        if (samples.length == 0) return true;
        double sumSquares = 0;
        for (short sample : samples) {
            sumSquares += (double) sample * sample;
        }
        double rms = Math.sqrt(sumSquares / samples.length);
        return rms < SILENCE_RMS_THRESHOLD;
    }

    // ── 파싱·직렬화 ───────────────────────────────────────

    /** 음성 지문 파싱 결과 */
    record AudioFingerprint(long durationMicros, long intervalMs,
                            List<Long> hashes, List<Boolean> silentFlags) {}

    /**
     * 음성 지문 문자열을 파싱한다.
     * 형식이 맞지 않으면 null을 반환한다.
     */
    AudioFingerprint parse(String value) {
        if (value == null || !value.startsWith(FORMAT_PREFIX + "|")) {
            return null;
        }
        String[] parts = value.split("\\|", 4);
        if (parts.length < 4) return null;
        try {
            long durationMicros = Long.parseLong(parts[1]);
            long intervalMs = Long.parseLong(parts[2]);
            List<Long> hashes = parseHashes(parts[3]);
            // 무음 플래그: hash가 0인 세그먼트를 무음으로 간주
            List<Boolean> silentFlags = hashes.stream()
                    .map(h -> h == 0L)
                    .toList();
            return new AudioFingerprint(durationMicros, intervalMs, hashes, silentFlags);
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

    // ── 유틸리티 ──────────────────────────────────────────

    private short[] toShortArray(List<Short> list, int count) {
        short[] arr = new short[count];
        for (int i = 0; i < count; i++) {
            arr[i] = list.get(i);
        }
        return arr;
    }

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
