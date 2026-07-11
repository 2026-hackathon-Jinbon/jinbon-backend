package com.jinbon.domain.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "신분증 검증 요청")
public record VerifyRequest(
        @Schema(description = "인증사 코드", example = "comdl") String provider,
        @Schema(description = "STEP 1에서 발급받은 token", example = "eyJhbGciOiJIUzI1NiJ9...") String token,
        @Schema(description = "STEP 1에서 발급받은 txId", example = "a80161c7c7fe4dcb93f6d6e2da7a9537pdrmn0ck") String txId,
        @Schema(description = "STEP 2에서 발급받은 cxId", example = "b12345...") String cxId
) {}
