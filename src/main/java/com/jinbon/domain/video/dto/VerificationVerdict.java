package com.jinbon.domain.video.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "영상 검증 판정",
        allowableValues = {"EXACT_MATCH", "SAME_CONTENT", "SIMILAR_MATCH", "REGISTERED_BUT_REVOKED",
                "CERTIFICATE_MISSING", "CERTIFICATE_INVALID", "NOT_REGISTERED", "VERIFICATION_UNAVAILABLE"})
public enum VerificationVerdict {
    EXACT_MATCH,
    SAME_CONTENT,
    SIMILAR_MATCH,
    REGISTERED_BUT_REVOKED,
    CERTIFICATE_MISSING,
    CERTIFICATE_INVALID,
    NOT_REGISTERED,
    VERIFICATION_UNAVAILABLE
}
