package com.jinbon.domain.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
@Schema(description = "인증 성공 응답")
public class AuthResponse {

    @Schema(description = "JWT 액세스 토큰 (Authorization 헤더에 Bearer {token} 형태로 사용)", example = "eyJhbGciOiJIUzI1NiJ9...")
    private String accessToken;

    @Schema(description = "회원 ID", example = "1")
    private Long memberId;

    @Schema(description = "회원 이름 (모바일 운전면허증에서 추출)", example = "홍길동")
    private String name;

    @Schema(description = "회원 역할 (USER, ADMIN)", example = "USER")
    private String role;
}
