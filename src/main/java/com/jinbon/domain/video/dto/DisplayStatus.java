package com.jinbon.domain.video.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "클라이언트 표시용 검증 상태")
public enum DisplayStatus {
    @Schema(description = "원본 일치 — 파일 SHA-256과 블록체인·VC 검증을 통과한 영상")
    AUTHENTICATED,

    @Schema(description = "콘텐츠 유사 — 등록 영상의 변환 가능성, 원본 일치 아님")
    CONTENT_SIMILAR,

    @Schema(description = "부분 유사 — 원본 일치 확인 불가")
    PARTIAL_SIMILAR,

    @Schema(description = "미인증 — 등록 이력이 없거나 보증서가 유효하지 않은 영상")
    NOT_AUTHENTICATED,

    @Schema(description = "확인 중 — 외부 시스템 장애로 일시적으로 확인 불가")
    UNAVAILABLE
}