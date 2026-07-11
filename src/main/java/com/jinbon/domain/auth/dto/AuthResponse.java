package com.jinbon.domain.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "인증 성공 응답")
public record AuthResponse(
        @Schema(description = "JWT 액세스 토큰", example = "eyJhbGciOiJIUzI1NiJ9...") String accessToken,
        @Schema(description = "JWT 리프레시 토큰", example = "eyJhbGciOiJIUzI1NiJ9...") String refreshToken,
        @Schema(description = "회원 ID", example = "1") Long memberId,
        @Schema(description = "회원 이름", example = "홍길동") String name,
        @Schema(description = "회원 역할", example = "USER") String role
) {}
