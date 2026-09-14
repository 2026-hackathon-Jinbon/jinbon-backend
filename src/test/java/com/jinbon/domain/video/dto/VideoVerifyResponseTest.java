package com.jinbon.domain.video.dto;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class VideoVerifyResponseTest {

    @Test
    void notRegisteredDoesNotDescribeTheVideoAsFake() {
        VideoVerifyResponse response = VideoVerifyResponse.notRegistered();

        assertThat(response.verdict()).isEqualTo(VerificationVerdict.NOT_REGISTERED);
        assertThat(response.displayStatus()).isEqualTo(DisplayStatus.NOT_AUTHENTICATED);
        assertThat(response.authentic()).isFalse();
        assertThat(response.notice()).contains("조작되었다는 의미가 아닙니다");
    }

    @Test
    void deactivatedVideoHasExplicitRevokedVerdict() {
        VideoVerifyResponse response = VideoVerifyResponse.deactivated(
                1L, "did:omn:issuer", LocalDateTime.now());

        assertThat(response.verdict()).isEqualTo(VerificationVerdict.REGISTERED_BUT_REVOKED);
        assertThat(response.displayStatus()).isEqualTo(DisplayStatus.NOT_AUTHENTICATED);
        assertThat(response.active()).isFalse();
    }

    @Test
    void onlyExactMatchMapsToAuthenticatedDisplayStatus() {
        assertThat(VerificationVerdict.EXACT_MATCH.toDisplayStatus()).isEqualTo(DisplayStatus.AUTHENTICATED);
        assertThat(VerificationVerdict.SAME_CONTENT.toDisplayStatus()).isEqualTo(DisplayStatus.CONTENT_SIMILAR);
        assertThat(VerificationVerdict.SIMILAR_MATCH.toDisplayStatus()).isEqualTo(DisplayStatus.CONTENT_SIMILAR);
        assertThat(VerificationVerdict.PARTIAL_MATCH.toDisplayStatus()).isEqualTo(DisplayStatus.PARTIAL_SIMILAR);
    }

    @Test
    void unavailableVerdictMapsToUnavailableDisplayStatus() {
        assertThat(VerificationVerdict.VERIFICATION_UNAVAILABLE.toDisplayStatus())
                .isEqualTo(DisplayStatus.UNAVAILABLE);
    }
}
