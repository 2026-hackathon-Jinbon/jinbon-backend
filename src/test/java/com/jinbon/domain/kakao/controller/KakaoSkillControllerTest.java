package com.jinbon.domain.kakao.controller;

import com.jinbon.domain.kakao.service.KakaoCallbackService;
import com.jinbon.domain.video.dto.VideoVerifyResponse;
import com.jinbon.domain.video.dto.VerificationVerdict;
import com.jinbon.domain.video.dto.SegmentMatchResult;
import com.jinbon.domain.video.dto.GapRange;
import com.jinbon.domain.video.service.VideoVerifyService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.Mockito.*;

class KakaoSkillControllerTest {
    private final VideoVerifyService videos = mock(VideoVerifyService.class);
    private final KakaoCallbackService callbacks = mock(KakaoCallbackService.class);
    private final KakaoSkillController controller = new KakaoSkillController(videos, callbacks);
    private static final String URL = "https://www.youtube.com/watch?v=RF14m35dFRU";
    private static final String CALLBACK = "https://bot-api.kakao.com/callback/test-token";

    @Test void acknowledgesCallbackBeforeRunningSlowVerification() {
        when(videos.verifyByUrl(URL)).thenAnswer(invocation -> {
            Thread.sleep(6000);
            return VideoVerifyResponse.notRegistered();
        });
        Map<String, Object> response = assertTimeoutPreemptively(Duration.ofSeconds(1),
                () -> controller.verify(payload(URL, CALLBACK)));
        assertThat(response).containsEntry("version", "2.0").containsEntry("useCallback", true)
                .doesNotContainKey("template");
        verify(callbacks).submit(eq(CALLBACK), any());
        verifyNoInteractions(videos);
    }

    @Test void backgroundVerificationProducesRealResultOrFailureMessage() {
        controller.verify(payload(URL, CALLBACK));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Supplier<Map<String, Object>>> task = ArgumentCaptor.forClass(Supplier.class);
        verify(callbacks).submit(eq(CALLBACK), task.capture());
        when(videos.verifyByUrl(URL)).thenReturn(VideoVerifyResponse.notRegistered());
        assertThat(task.getValue().get().toString()).contains("등록 기록 없음");
        when(videos.verifyByUrl(URL)).thenThrow(new IllegalStateException("download failed"));
        assertThat(task.getValue().get().toString()).contains("영상을 확인하지 못했어요");
    }

    @Test void missingVideoLinkStillReturnsImmediateGuidance() {
        assertThat(controller.verify(payload("진본 확인", CALLBACK)).toString()).contains("영상 링크를 보내주세요");
        verifyNoInteractions(videos, callbacks);
    }

    @Test void requestsWithoutCallbackRetainExistingResponse() {
        when(videos.verifyByUrl(URL)).thenReturn(VideoVerifyResponse.notRegistered());
        assertThat(controller.verify(Map.of("userRequest", Map.of("utterance", URL))).toString())
                .contains("등록 기록 없음").doesNotContain("useCallback");
        verifyNoInteractions(callbacks);
    }

    @Test void fullQueueReturnsImmediateFailureInsteadOfFalseAcknowledgement() {
        doThrow(new RejectedExecutionException()).when(callbacks).submit(eq(CALLBACK), any());
        assertThat(controller.verify(payload(URL, CALLBACK)).toString())
                .contains("접수하지 못했어요").doesNotContain("useCallback");
        verifyNoInteractions(videos);
    }

    @Test void authenticReplyContainsConciseEvidenceWithoutDisclaimersOrInternalId() {
        var result = result(VerificationVerdict.SIMILAR_MATCH, true, comparison(false), comparison(false));
        when(videos.verifyByUrl(URL)).thenReturn(result);
        String reply = controller.verify(Map.of("userRequest", Map.of("utterance", URL))).toString();
        assertThat(reply).contains("✅ 진본 확인", "등록자 표시명: 김다연", "확인 방식: 영상·음성 비교",
                "등록 증거: 블록체인·보증서 확인됨", "등록 시각: 2026.09.18 18:48")
                .doesNotContain("기관 소속", "직함", "무변조", "등록 영상 ID", "진본 확인 완료");
    }

    @ParameterizedTest
    @CsvSource({"true,false,음성 비교", "false,true,영상 비교", "true,true,영상·음성 비교"})
    void identifiesWhichMediaDoesNotMatch(boolean audioMismatch, boolean videoMismatch, String expected) {
        when(videos.verifyByUrl(URL)).thenReturn(result(VerificationVerdict.CONTENT_SIMILAR, false,
                comparison(videoMismatch), comparison(audioMismatch)));
        String reply = controller.verify(Map.of("userRequest", Map.of("utterance", URL))).toString();
        assertThat(reply).contains("⚠️ 원본 불일치", expected + "에서 불일치", "비교 원본")
                .doesNotContain("✅", "진본 확인 완료", "가짜", "딥페이크");
    }

    @Test void missingComparisonDataIsNotReportedAsDetectedMismatch() {
        when(videos.verifyByUrl(URL)).thenReturn(result(VerificationVerdict.CONTENT_SIMILAR, false,
                comparison(true), SegmentMatchResult.unavailable()));
        String reply = controller.verify(Map.of("userRequest", Map.of("utterance", URL))).toString();
        assertThat(reply).contains("⚠️ 확인 불가").doesNotContain("⚠️ 원본 불일치", "✅");
    }

    @Test void certificateFailureIsNotPresentedAsConfirmedEvidence() {
        var result = VideoVerifyResponse.of(VerificationVerdict.CERTIFICATE_INVALID, null, false,
                13L, "did:example:issuer", null, "김다연", true, true, false, true,
                "보증서가 유효하지 않습니다.", null);
        when(videos.verifyByUrl(URL)).thenReturn(result);
        assertThat(controller.verify(Map.of("userRequest", Map.of("utterance", URL))).toString())
                .contains("⚠️ 확인 불가", "등록 증거: 검증 미완료")
                .doesNotContain("보증서 확인됨", "✅");
    }

    private VideoVerifyResponse result(VerificationVerdict verdict, boolean authentic,
                                       SegmentMatchResult video, SegmentMatchResult audio) {
        return VideoVerifyResponse.of(verdict, 1.0, authentic, 13L, "did:example:issuer",
                LocalDateTime.of(2026, 9, 18, 18, 48, 23), "김다연", true, true, true, true,
                authentic ? "등록 원본과 영상·음성 유사도 기준을 통과했습니다." : "진본 여부를 확인할 수 없습니다.",
                "모든 프레임·음성의 무변조를 보증하지 않습니다.", video, audio);
    }

    private SegmentMatchResult comparison(boolean mismatch) {
        return new SegmentMatchResult(mismatch ? 0.5 : 1.0, true, 0, 0, 2000,
                mismatch ? 1 : 2, 2, 2, mismatch ? List.of(new GapRange(0, 1000)) : List.of());
    }

    private Map<String, Object> payload(String utterance, String callback) {
        return Map.of("userRequest", Map.of("utterance", utterance, "callbackUrl", callback));
    }
}
