package com.jinbon.domain.video.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Path;

/**
 * 검증 파이프라인에서 사용하는 영상·세그먼트·음성 지문 생성을 한 곳에서 담당한다.
 * 개별 지문 생성 실패는 해당 비교만 불가능하게 하고, 기본 영상 pHash 실패는 전체 처리를 중단한다.
 */
@Service
@RequiredArgsConstructor
public class MediaFingerprintService {

    private final PerceptualHashService perceptualHashService;
    private final VideoFingerprintService videoFingerprintService;
    private final AudioFingerprintService audioFingerprintService;

    public Fingerprints generate(MultipartFile file) throws IOException {
        return generate(
                perceptualHashService.generateFingerprint(file),
                () -> videoFingerprintService.generate(file),
                () -> audioFingerprintService.generate(file)
        );
    }

    public Fingerprints generate(Path file) throws IOException {
        return generate(
                perceptualHashService.generateFingerprint(file),
                () -> videoFingerprintService.generate(file),
                () -> audioFingerprintService.generate(file)
        );
    }

    private Fingerprints generate(String perceptual, CheckedGenerator segment,
                                  CheckedGenerator audio) {
        return new Fingerprints(perceptual, optional(segment), optional(audio));
    }

    private String optional(CheckedGenerator generator) {
        try {
            return generator.generate();
        } catch (IOException e) {
            return null;
        }
    }

    @FunctionalInterface
    private interface CheckedGenerator {
        String generate() throws IOException;
    }

    public record Fingerprints(String perceptual, String segment, String audio) {
    }
}
