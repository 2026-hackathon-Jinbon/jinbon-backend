package com.jinbon.domain.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "토큰 갱신 요청")
public record RefreshRequest(
        @Schema(description = "리프레시 토큰", example = "eyJhbGciOiJIUzI1NiJ9...") String refreshToken
) {}
