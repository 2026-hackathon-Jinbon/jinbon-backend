package com.jinbon.domain.video.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "영상 검증 판정 (내부용)",
        allowableValues = {"EXACT_MATCH", "SAME_CONTENT", "SIMILAR_MATCH", "CONTENT_SIMILAR",
                "PARTIAL_MATCH", "REGISTERED_BUT_REVOKED",
                "CERTIFICATE_MISSING", "CERTIFICATE_INVALID", "NOT_REGISTERED", "VERIFICATION_UNAVAILABLE"})
public enum VerificationVerdict {
    /** 원본 파일과 SHA-256 일치 */
    EXACT_MATCH(DisplayStatus.AUTHENTICATED),
    /** 프레임 지각해시 완전 일치 — 재인코딩·컨테이너 변경본 */
    SAME_CONTENT(DisplayStatus.AUTHENTICATED),
    /** 지각해시 + 세그먼트 커버리지 통과 — 플랫폼 재인코딩본 */
    SIMILAR_MATCH(DisplayStatus.AUTHENTICATED),
    /** 원본 후보는 찾았지만 대응 구간의 무변조까지 확정하지 못함 */
    CONTENT_SIMILAR(DisplayStatus.NOT_AUTHENTICATED),
    /** 일부 프레임만 유사 — 진본으로 인정하지 않음 */
    PARTIAL_MATCH(DisplayStatus.NOT_AUTHENTICATED),
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
