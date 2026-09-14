package com.jinbon.domain.video.service;

import com.jinbon.domain.video.dto.SegmentMatchResult;
import com.jinbon.domain.video.dto.VerificationVerdict;
import com.jinbon.domain.video.entity.Video;
import com.jinbon.domain.video.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 등록 영상 후보 검색과 영상·음성 지문 비교를 담당한다.
 * 블록체인, VC, 응답 메시지 조합은 VideoVerifyService가 담당한다.
 */
@Service
@RequiredArgsConstructor
public class VideoContentMatchService {

    private static final double VIDEO_COVERAGE_HIGH = 0.95;
    private static final double VIDEO_COVERAGE_PARTIAL = 0.80;
    private static final double AUDIO_COVERAGE_HIGH = 0.90;

    private final VideoRepository videoRepository;
    private final PerceptualHashService perceptualHashService;
    private final VideoFingerprintService videoFingerprintService;
    private final AudioFingerprintService audioFingerprintService;

    public Optional<Match> find(String perceptual, String videoSegments, String audioSegments) {
        Video bestVideo = null;
        PerceptualHashService.Comparison best = null;

        for (Video video : videoRepository.findByActiveTrue()) {
            if (video.getPerceptualHash() == null) continue;
            var comparison = perceptualHashService.compare(perceptual, video.getPerceptualHash());
            if (!comparison.similar() && !comparison.partial()) continue;
            if (best == null || (comparison.similar() && !best.similar())
                    || (comparison.similar() == best.similar()
                    && comparison.meanDistance() < best.meanDistance())) {
                best = comparison;
                bestVideo = video;
            }
        }

        if (bestVideo == null) return Optional.empty();

        SegmentMatchResult videoMatch = compareVideo(videoSegments, bestVideo);
        SegmentMatchResult audioMatch = compareAudio(audioSegments, bestVideo);
        VerificationVerdict verdict = refine(best, videoMatch, audioMatch);
        return Optional.of(new Match(bestVideo, verdict, best.meanDistance(), videoMatch, audioMatch));
    }

    private SegmentMatchResult compareVideo(String input, Video reference) {
        if (input == null || reference.getSegmentFingerprint() == null) return null;
        return videoFingerprintService.compare(input, reference.getSegmentFingerprint());
    }

    private SegmentMatchResult compareAudio(String input, Video reference) {
        if (input == null || reference.getAudioFingerprint() == null) return null;
        return audioFingerprintService.compare(input, reference.getAudioFingerprint());
    }

    private VerificationVerdict refine(PerceptualHashService.Comparison pHash,
                                       SegmentMatchResult video,
                                       SegmentMatchResult audio) {
        if (video == null || video.totalRefSegments() == 0) {
            return pHash.similar()
                    ? VerificationVerdict.CONTENT_SIMILAR
                    : VerificationVerdict.PARTIAL_MATCH;
        }

        boolean videoHigh = video.coverage() >= VIDEO_COVERAGE_HIGH && video.orderPreserved();
        boolean audioAvailable = audio != null && audio.totalRefSegments() > 0;
        boolean audioHigh = audioAvailable
                && audio.coverage() >= AUDIO_COVERAGE_HIGH
                && audio.orderPreserved();

        if (videoHigh && audioHigh) {
            // 전체 영상 또는 등록 원본의 연속 구간 인증
            return VerificationVerdict.SIMILAR_MATCH;
        }
        if (pHash.similar() || video.coverage() >= VIDEO_COVERAGE_PARTIAL) {
            return VerificationVerdict.CONTENT_SIMILAR;
        }
        return VerificationVerdict.PARTIAL_MATCH;
    }

    public record Match(Video video, VerificationVerdict verdict, double similarityDistance,
                        SegmentMatchResult videoMatch, SegmentMatchResult audioMatch) {
    }
}
