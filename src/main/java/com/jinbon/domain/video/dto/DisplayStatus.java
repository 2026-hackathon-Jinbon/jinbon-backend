package com.jinbon.domain.video.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "클라이언트 표시용 검증 상태")
public enum DisplayStatus {
    @Schema(description = "진본 인증 — 블록체인에 등록이 확인된 영상")
    AUTHENTICATED,

    @Schema(description = "미인증 — 등록 이력이 없거나 보증서가 유효하지 않은 영상")
    NOT_AUTHENTICATED,

    @Schema(description = "확인 중 — 외부 시스템 장애로 일시적으로 확인 불가")
    UNAVAILABLE
}