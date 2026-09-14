package com.jinbon.domain.video.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "영상 검증 판정 (내부용)",
        allowableValues = {"EXACT_MATCH", "SAME_CONTENT", "SIMILAR_MATCH", "PARTIAL_MATCH", "REGISTERED_BUT_REVOKED",
                "CERTIFICATE_MISSING", "CERTIFICATE_INVALID", "NOT_REGISTERED", "VERIFICATION_UNAVAILABLE"})
public enum VerificationVerdict {
    EXACT_MATCH(DisplayStatus.AUTHENTICATED),
    SAME_CONTENT(DisplayStatus.CONTENT_SIMILAR),
    SIMILAR_MATCH(DisplayStatus.CONTENT_SIMILAR),
    PARTIAL_MATCH(DisplayStatus.PARTIAL_SIMILAR),
    REGISTERED_BUT_REVOKED(DisplayStatus.NOT_AUTHENTICATED),
    CERTIFICATE_MISSING(DisplayStatus.NOT_AUTHENTICATED),
    CERTIFICATE_INVALID(DisplayStatus.NOT_AUTHENTICATED),
    NOT_REGISTERED(DisplayStatus.NOT_AUTHENTICATED),
    VERIFICATION_UNAVAILABLE(DisplayStatus.UNAVAILABLE);

    private final DisplayStatus displayStatus;

    VerificationVerdict(DisplayStatus displayStatus) {
        this.displayStatus = displayStatus;
    }

    public DisplayStatus toDisplayStatus() {
        return displayStatus;
    }
}
