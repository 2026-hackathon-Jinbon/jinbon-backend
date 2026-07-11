package com.jinbon.global.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SwaggerConfig {

    @Bean
    public OpenAPI openAPI() {
        String securitySchemeName = "Bearer Token";

        return new OpenAPI()
                .info(new Info()
                        .title("진본(JinBon) API")
                        .description("""
                                영상 진본 확인 서비스 API

                                ## 모바일 운전면허증 기반 인증 흐름 (프론트엔드 호출 순서)

                                ### PC/웹 (QR 방식)
                                1. `POST /api/auth/token` — OmniOne CX 토큰 발급 (txId, token 획득)
                                2. `POST /api/auth/qr/request` — QR 코드 생성 요청 (provider=comdl)
                                3. 사용자가 모바일 운전면허증 앱으로 QR 스캔 → 신분증 제출
                                4. `POST /api/auth/qr/verify` — 검증 + 자동 회원가입 + JWT 발급

                                ### 모바일 앱 (WebToApp/AppToApp 방식)
                                1. `POST /api/auth/token` — OmniOne CX 토큰 발급 (txId, token 획득)
                                2. `POST /api/auth/app/request` — 딥링크 URL 생성 요청 (provider=comdl)
                                3. 딥링크로 모바일 운전면허증 앱 호출 → 신분증 제출
                                4. `POST /api/auth/app/verify` — 검증 + 자동 회원가입 + JWT 발급

                                ### 인증 후
                                - 응답으로 받은 `accessToken`을 `Authorization: Bearer {token}` 헤더에 담아 요청
                                - 영상 등록 등 인증이 필요한 API 호출 시 사용

                                ### Provider 목록
                                | provider | 이름 |
                                |---|---|
                                | `comdl` | 모바일 운전면허증 (권장) |
                                | `coidentitydocument` | 모바일 신분증 |
                                """)
                        .version("1.0.0"))
                .addSecurityItem(new SecurityRequirement().addList(securitySchemeName))
                .components(new Components()
                        .addSecuritySchemes(securitySchemeName, new SecurityScheme()
                                .name(securitySchemeName)
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("모바일 운전면허증 인증 후 발급받은 JWT 토큰")));
    }
}
