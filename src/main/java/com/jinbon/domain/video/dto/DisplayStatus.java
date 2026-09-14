package com.jinbon.domain.video.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "클라이언트 표시용 검증 상태")
public enum DisplayStatus {
    @Schema(description = "진본 — 등록 원본과 파일이 일치하거나 지각해시로 같은 내용임이 확인되고, 블록체인·VC 검증을 통과한 영상")
    AUTHENTICATED,

    @Schema(description = "미인증 — 등록 이력이 없거나, 등록 취소, 보증서 미발급·무효, 일부 프레임만 유사한 영상")
    NOT_AUTHENTICATED,

    @Schema(description = "확인 중 — 외부 시스템 장애로 일시적으로 확인 불가 (재시도 유도)")
    UNAVAILABLE
}
