package com.jinbon.domain.auth.controller;

import com.jinbon.domain.auth.dto.AuthResponse;
import com.jinbon.domain.auth.dto.VerifyRequest;
import com.jinbon.global.common.CommonResponse;
import com.jinbon.infra.omnione.dto.OacxAppResponse;
import com.jinbon.infra.omnione.dto.OacxTokenResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

@Tag(name = "인증", description = """
        모바일 운전면허증(OmniOne CX) 기반 본인인증 및 로그인 API

        ## 호출 순서 (모바일 앱 — WebToApp 방식)
        ```
        1. POST /api/auth/token         → token, txId 획득
        2. POST /api/auth/app/request   → 딥링크 URL + cxId 획득
        3. 딥링크로 모바일 운전면허증 앱 호출
        4. POST /api/auth/app/verify    → JWT accessToken 획득
        ```
        """)
public interface AuthApi {

    @Operation(
            summary = "[STEP 1] OmniOne CX 토큰 발급",
            description = """
                    인증 흐름의 첫 번째 단계입니다.
                    OmniOne CX 서버에서 인증 세션용 토큰과 트랜잭션 ID를 발급받습니다.

                    **응답값 중 `token`과 `txId`를 이후 API 호출에 사용합니다.**
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "토큰 발급 성공")
    })
    ResponseEntity<CommonResponse<OacxTokenResponse>> createToken();

    @Operation(
            summary = "[STEP 2] WebToApp 인증 요청",
            description = """
                    모바일 앱 환경에서 딥링크를 생성합니다.

                    **응답의 `data`에 플랫폼별 딥링크 URL이 포함됩니다:**
                    - `androidLink` — Android용 딥링크
                    - `iosLink` — iOS용 딥링크

                    이 딥링크로 모바일 운전면허증 앱을 호출합니다.
                    응답의 `cxId`는 검증(STEP 3) 호출 시 필요합니다.
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "딥링크 생성 성공")
    })
    ResponseEntity<CommonResponse<OacxAppResponse>> requestApp(
            @Parameter(description = "인증사 코드 (모바일 운전면허증: comdl)", example = "comdl", required = true)
            String provider,
            @Parameter(description = "STEP 1에서 발급받은 token", required = true)
            String token,
            @Parameter(description = "STEP 1에서 발급받은 txId", required = true)
            String txId);

    @Operation(
            summary = "[STEP 3] WebToApp 검증 및 로그인",
            description = """
                    사용자가 딥링크를 통해 신분증을 제출한 후 호출합니다.

                    **처리 흐름:**
                    1. OmniOne CX에서 신분증 검증
                    2. 검증된 신원정보(CI, 이름, 생년월일) 추출
                    3. CI 기반 자동 회원가입 또는 기존 회원 조회
                    4. JWT accessToken 발급

                    **응답의 `accessToken`을 이후 API 호출 시 `Authorization: Bearer {token}` 헤더에 포함하세요.**
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "인증 성공, JWT 발급"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "신분증 검증 실패")
    })
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            description = "검증 요청 데이터 (STEP 1, 2에서 받은 값들 + provider)",
            required = true,
            content = @Content(
                    schema = @Schema(implementation = VerifyRequest.class),
                    examples = @ExampleObject(value = """
                            {
                              "provider": "comdl",
                              "token": "eyJhbGciOiJIUzI1NiJ9...",
                              "txId": "a80161c7c7fe4dcb93f6d6e2da7a9537pdrmn0ck",
                              "cxId": "b12345..."
                            }
                            """)
            )
    )
    ResponseEntity<CommonResponse<AuthResponse>> verifyApp(VerifyRequest request);
}
