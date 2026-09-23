package com.jinbon.domain.kakao.controller;

import com.jinbon.domain.kakao.service.KakaoCallbackService;
import com.jinbon.domain.video.dto.VideoVerifyResponse;
import com.jinbon.domain.video.dto.SegmentMatchResult;
import com.jinbon.domain.video.dto.VerificationVerdict;
import com.jinbon.domain.video.service.VideoVerifyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@RestController
@RequestMapping("/api/kakao/skill")
@RequiredArgsConstructor
public class KakaoSkillController {

    private static final Pattern URL_PATTERN = Pattern.compile("https?://\\S+");

    private final VideoVerifyService videoVerifyService;
    private final KakaoCallbackService callbackService;

    @PostMapping("/verify")
    public Map<String, Object> verify(@RequestBody Map<String, Object> payload) {
        String utterance = extractUtterance(payload);
        String url = extractUrl(utterance);

        if (url == null) {
            return simpleText("🎥 확인하고 싶은 영상 링크를 보내주세요.\n\n유튜브, 쇼츠 링크 모두 괜찮아요.\n예: https://www.youtube.com/watch?v=...");
        }

        Object userRequest = payload.get("userRequest");
        Object callbackUrl = userRequest instanceof Map<?, ?> request ? request.get("callbackUrl") : null;
        if (callbackUrl instanceof String callback && !callback.isBlank()) {
            try {
                callbackService.submit(callback, () -> verifyVideo(url));
                log.info("Kakao skill accepted for callback verification");
                return Map.of("version", "2.0", "useCallback", true);
            } catch (IllegalArgumentException | RejectedExecutionException e) {
                log.warn("Kakao callback request rejected - errorType={}", e.getClass().getSimpleName());
                return simpleText("검증 요청을 접수하지 못했어요. 잠시 후 다시 시도해주세요.");
            }
        }

        return verifyVideo(url);
    }

    private Map<String, Object> verifyVideo(String url) {
        try {
            VideoVerifyResponse result = videoVerifyService.verifyByUrl(url);
            return simpleText(formatResult(result));
        } catch (Exception e) {
            log.warn("Kakao skill video verification failed - url={}, reason={}", url, e.getMessage());
            return simpleText("잠시 문제가 생겨 영상을 확인하지 못했어요. 🙏\n\n서버 상태를 확인한 뒤 다시 시도해주세요.");
        }
    }

    @SuppressWarnings("unchecked")
    private String extractUtterance(Map<String, Object> payload) {
        Object userRequest = payload.get("userRequest");
        if (!(userRequest instanceof Map<?, ?> request)) {
            return "";
        }

        Object utterance = ((Map<String, Object>) request).get("utterance");
        return utterance == null ? "" : utterance.toString();
    }

    private String extractUrl(String text) {
        Matcher matcher = URL_PATTERN.matcher(text == null ? "" : text);
        if (!matcher.find()) {
            return null;
        }
        return matcher.group().replaceAll("[\\])}>.,!?]+$", "");
    }

    private String formatResult(VideoVerifyResponse result) {
        VerificationVerdict verdict = result.verdict();
        boolean comparisonFailed = verdict == VerificationVerdict.CONTENT_SIMILAR
                || verdict == VerificationVerdict.PARTIAL_MATCH;
        boolean comparisonAvailable = hasComparison(result.segmentMatch()) && hasComparison(result.audioMatch());
        boolean audioMismatch = comparisonFailed && comparisonAvailable && !result.audioMatch().unmatchedRanges().isEmpty();
        boolean videoMismatch = comparisonFailed && comparisonAvailable && !result.segmentMatch().unmatchedRanges().isEmpty();

        String title;
        if (result.authentic()) {
            title = "✅ 진본 확인";
        } else if (audioMismatch || videoMismatch) {
            title = "⚠️ 원본 불일치";
        } else if (verdict == VerificationVerdict.NOT_REGISTERED) {
            title = "🔎 등록 기록 없음";
        } else {
            title = "⚠️ 확인 불가";
        }
        String message = audioMismatch && videoMismatch ? "등록 원본과의 영상·음성 비교에서 불일치가 확인되었습니다."
                : audioMismatch ? "등록 영상과의 음성 비교에서 불일치가 확인되었습니다."
                : videoMismatch ? "등록 원본과의 영상 비교에서 불일치가 확인되었습니다."
                : result.message();

        List<String> details = new ArrayList<>();
        if (verdict == VerificationVerdict.EXACT_MATCH) {
            details.add("확인 방식: 원본 파일 정확 일치");
        } else if (verdict == VerificationVerdict.SAME_CONTENT || verdict == VerificationVerdict.SIMILAR_MATCH) {
            details.add("확인 방식: 영상·음성 비교");
        }
        if (result.registrantName() != null && !result.registrantName().isBlank()) {
            details.add("등록자 표시명: " + result.registrantName());
        }
        if (result.videoId() != null) {
            details.add("등록 증거: " + (result.blockchainVerified() && result.vcVerified() && result.vcClaimsBound()
                    ? "블록체인·보증서 확인됨" : "검증 미완료"));
        }
        if (result.registeredAt() != null) {
            details.add("등록 시각: " + result.registeredAt().format(DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm")));
        }
        return title + "\n\n" + message + (details.isEmpty() ? ""
                : "\n\n" + (result.authentic() ? "" : "비교 원본\n") + String.join("\n", details));
    }

    private boolean hasComparison(SegmentMatchResult comparison) {
        return comparison != null && comparison.totalQuerySegments() > 0 && comparison.totalRefSegments() > 0;
    }

    private Map<String, Object> simpleText(String text) {
        return Map.of(
                "version", "2.0",
                "template", Map.of(
                        "outputs", List.of(
                                Map.of("simpleText", Map.of("text", text))
                        )
                )
        );
    }
}
