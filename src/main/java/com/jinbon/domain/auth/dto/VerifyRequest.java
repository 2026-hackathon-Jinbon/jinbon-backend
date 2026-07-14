package com.jinbon.domain.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * 모바일 신분증 검증 요청 DTO.
 * OmniOne CX 인증 세션에서 발급받은 토큰과 트랜잭션 ID를 전달한다.
 */
@Schema(description = "신분증 검증 요청")
public record VerifyRequest(
        @Schema(description = "인증사 코드", example = "comdl")
        @NotBlank String provider,

        @Schema(description = "STEP 1에서 발급받은 token", example = "eyJhbGciOiJIUzI1NiJ9...")
        @NotBlank String token,

        @Schema(description = "STEP 1에서 발급받은 txId", example = "a80161c7c7fe4dcb93f6d6e2da7a9537pdrmn0ck")
        @NotBlank String txId,

        @Schema(description = "STEP 2에서 발급받은 cxId", example = "b12345...")
        @NotBlank String cxId
) {}
