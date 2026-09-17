package com.jinbon.domain.kakao.controller;

import com.jinbon.domain.video.dto.VideoVerifyResponse;
import com.jinbon.domain.video.dto.VerificationVerdict;
import com.jinbon.domain.video.service.VideoVerifyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@RestController
@RequestMapping("/api/kakao/skill")
@RequiredArgsConstructor
public class KakaoSkillController {

    private static final Pattern URL_PATTERN = Pattern.compile("https?://\\S+");

    private final VideoVerifyService videoVerifyService;

    @PostMapping("/verify")
    public Map<String, Object> verify(@RequestBody Map<String, Object> payload) {
        String utterance = extractUtterance(payload);
        String url = extractUrl(utterance);

        if (url == null) {
            return simpleText("🎥 확인하고 싶은 영상 링크를 보내주세요.\n\n유튜브, 쇼츠 링크 모두 괜찮아요.\n예: https://www.youtube.com/watch?v=...");
        }

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

        if (verdict == VerificationVerdict.NOT_REGISTERED) {
            return "🔎 진본 기록을 찾지 못했어요.\n\n"
                    + "이 영상이 가짜라는 뜻은 아니에요.\n"
                    + "다만 현재 Jinbon에 등록된 원본 기록과는 매칭되지 않았어요.\n\n"
                    + "원본 등록 여부를 한 번 확인해주세요.";
        }

        if (verdict == VerificationVerdict.REGISTERED_BUT_REVOKED) {
            return "⚠️ 등록 기록은 있지만 현재 비활성화된 영상이에요.\n\n"
                    + "관리자 확인이 필요한 상태입니다.";
        }

        if (verdict == VerificationVerdict.VERIFICATION_UNAVAILABLE) {
            return "⏳ 등록 기록은 확인했지만, 지금은 외부 검증을 완료하지 못했어요.\n\n"
                    + "잠시 후 다시 시도해주세요.";
        }

        if (result.authentic()) {
            String message = switch (verdict) {
                case EXACT_MATCH -> "등록된 원본 영상과 정확히 일치해요.";
                case SAME_CONTENT, SIMILAR_MATCH -> "등록 원본과 영상·음성 유사도 기준을 통과했어요.";
                default -> result.message();
            };

            StringBuilder builder = new StringBuilder();
            builder.append("✅ 진본 확인 완료\n\n");
            builder.append(message);
            builder.append("\n확인 방식: ").append(verdict == VerificationVerdict.EXACT_MATCH
                    ? "원본 파일 정확 일치" : "영상·음성 비교");

            if (result.registrantName() != null) {
                builder.append("\n\n등록자 표시명: ").append(result.registrantName());
                builder.append("\n표시명은 기관 소속·직함의 인증을 뜻하지 않습니다.");
            }
            if (result.registeredAt() != null) {
                builder.append("\n등록 시각: ").append(result.registeredAt().toLocalDate())
                        .append(" ").append(result.registeredAt().toLocalTime().withNano(0));
            }
            if (result.videoId() != null) {
                builder.append("\n등록 영상 ID: ").append(result.videoId());
            }
            if (result.notice() != null) builder.append("\n\n").append(result.notice());

            return builder.toString();
        }

        return "🤔 진본 여부를 확정하지 못했어요.\n\n"
                + "영상 링크를 다시 확인하거나, 원본 등록 여부를 확인해주세요.";
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
