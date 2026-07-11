package com.jinbon.domain.video.service;

import lombok.extern.slf4j.Slf4j;
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
 * 영상에서 고정 간격으로 프레임을 추출하고,
 * 각 프레임의 64비트 pHash를 계산한다.
 * 재인코딩/리사이즈/압축에도 유사한 해시를 생성하여
 * 해밍 거리 기반 유사도 비교가 가능하다.
 */
@Slf4j
@Service
public class PerceptualHashService {

    /** 추출할 최대 프레임 수 */
    private static final int MAX_FRAMES = 10;

    /** 프레임 추출 간격 (초) */
    private static final double FRAME_INTERVAL_SECONDS = 2.0;

    /** DCT 계산용 이미지 크기 */
    private static final int DCT_SIZE = 32;

    /** 해시 추출용 저주파 영역 크기 */
    private static final int HASH_SIZE = 8;

    /** 동일 영상 판정 해밍 거리 임계값 */
    public static final int SIMILARITY_THRESHOLD = 10;

    /**
     * 영상 파일에서 지각해시 핑거프린트를 생성한다.
     * 고정 간격으로 프레임을 추출하고 각 프레임의 pHash를 계산한다.
     *
     * @return 프레임별 pHash 목록 (hex 문자열, 쉼표 구분)
     */
    public String generateFingerprint(MultipartFile file) throws IOException {
        File tempFile = File.createTempFile("jinbon-phash-", ".tmp");
        try {
            file.transferTo(tempFile);
            List<Long> frameHashes = extractFrameHashes(tempFile);

            if (frameHashes.isEmpty()) {
                throw new IOException("No frames could be extracted from video");
            }

            String fingerprint = framHashesToString(frameHashes);
            log.info("Perceptual fingerprint generated - frames={}, fingerprint={}...",
                    frameHashes.size(), fingerprint.substring(0, Math.min(32, fingerprint.length())));
            return fingerprint;
        } finally {
            Files.deleteIfExists(tempFile.toPath());
        }
    }

    /**
     * 파일 경로에서 지각해시 핑거프린트를 생성한다.
     * URL 다운로드 등 이미 파일이 존재하는 경우 사용한다.
     */
    public String generateFingerprint(Path videoFile) throws IOException {
        List<Long> frameHashes = extractFrameHashes(videoFile.toFile());

        if (frameHashes.isEmpty()) {
            throw new IOException("No frames could be extracted from video");
        }

        String fingerprint = framHashesToString(frameHashes);
        log.info("Perceptual fingerprint generated - frames={}, fingerprint={}...",
                frameHashes.size(), fingerprint.substring(0, Math.min(32, fingerprint.length())));
        return fingerprint;
    }

    /**
     * 두 핑거프린트의 유사도를 평균 해밍 거리로 계산한다.
     * 각 프레임에 대해 가장 가까운 매칭 프레임을 찾아 평균을 낸다.
     *
     * @return 평균 해밍 거리 (0 = 동일, 64 = 완전히 다름)
     */
    public double compareFingerprints(String fingerprint1, String fingerprint2) {
        List<Long> hashes1 = stringToFrameHashes(fingerprint1);
        List<Long> hashes2 = stringToFrameHashes(fingerprint2);

        if (hashes1.isEmpty() || hashes2.isEmpty()) {
            return 64.0;
        }

        // 각 프레임에 대해 가장 가까운 매칭 프레임의 해밍 거리를 합산
        int totalDistance = 0;
        for (long h1 : hashes1) {
            int minDist = 64;
            for (long h2 : hashes2) {
                minDist = Math.min(minDist, Long.bitCount(h1 ^ h2));
            }
            totalDistance += minDist;
        }

        return (double) totalDistance / hashes1.size();
    }

    /**
     * 유사 영상 여부를 판정한다.
     */
    public boolean isSimilar(String fingerprint1, String fingerprint2) {
        return compareFingerprints(fingerprint1, fingerprint2) < SIMILARITY_THRESHOLD;
    }

    /**
     * 영상에서 고정 간격으로 프레임을 추출하고 pHash를 계산한다.
     */
    private List<Long> extractFrameHashes(File videoFile) throws IOException {
        List<Long> hashes = new ArrayList<>();

        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(videoFile);
             Java2DFrameConverter converter = new Java2DFrameConverter()) {

            grabber.start();
            double durationSeconds = grabber.getLengthInTime() / 1_000_000.0;
            int numFrames = Math.min((int) (durationSeconds / FRAME_INTERVAL_SECONDS), MAX_FRAMES);
            numFrames = Math.max(numFrames, 1);

            log.debug("Extracting frames - duration={}s, numFrames={}", String.format("%.1f", durationSeconds), numFrames);

            for (int i = 0; i < numFrames; i++) {
                long timestamp = (long) ((i + 0.5) * durationSeconds / numFrames * 1_000_000);
                grabber.setTimestamp(timestamp);

                Frame frame = grabber.grabImage();
                if (frame == null) continue;

                BufferedImage image = converter.convert(frame);
                if (image == null) continue;

                long pHash = computePHash(image);
                hashes.add(pHash);
            }

            grabber.stop();
        } catch (Exception e) {
            throw new IOException("Failed to extract frames from video", e);
        }

        return hashes;
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
