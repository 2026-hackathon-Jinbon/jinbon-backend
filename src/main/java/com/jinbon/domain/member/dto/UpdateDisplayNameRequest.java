package com.jinbon.domain.member.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

@Schema(description = "등록자 표시명 변경 요청")
public record UpdateDisplayNameRequest(
        @Schema(description = "검증 결과에 노출할 표시명 (기관명·직함). 비우면 실명으로 표시", example = "기획재정부 대변인실")
        @Size(max = 100) String displayName
) {}
