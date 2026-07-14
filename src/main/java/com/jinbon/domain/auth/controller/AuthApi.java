package com.jinbon.domain.auth.controller;

import com.jinbon.domain.auth.dto.AuthResponse;
import com.jinbon.domain.auth.dto.RefreshRequest;
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
        4. POST /api/auth/app/verify    → JWT accessToken + refreshToken 획득
        ```

        ## 토큰 관리
        - Access Token: 30분 유효, `Authorization: Bearer {token}` 헤더에 포함
        - Refresh Token: 7일 유효, 만료 시 `POST /api/auth/refresh`로 갱신
        - 로그아웃: `POST /api/auth/logout`
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
                    3. CI로 가입 완료(ACTIVE) 회원 조회
                    4. JWT accessToken + refreshToken 발급

                    미가입 회원은 로그인할 수 없으며 `/api/signup` 흐름을 먼저 완료해야 합니다.

                    **응답의 `accessToken`을 이후 API 호출 시 `Authorization: Bearer {token}` 헤더에 포함하세요.**
                    **`refreshToken`은 accessToken 만료 시 갱신용으로 안전하게 보관하세요.**
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

    @Operation(
            summary = "토큰 갱신",
            description = """
                    Access Token이 만료되었을 때 Refresh Token으로 새 토큰 쌍을 발급받습니다.

                    **Refresh Token Rotation 적용:**
                    - 갱신 시 새로운 accessToken + refreshToken이 발급됩니다.
                    - 기존 refreshToken은 즉시 무효화됩니다.
                    - 새로 발급된 refreshToken을 저장하세요.
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "토큰 갱신 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "유효하지 않은 리프레시 토큰")
    })
    ResponseEntity<CommonResponse<AuthResponse>> refresh(RefreshRequest request);

    @Operation(
            summary = "로그아웃",
            description = """
                    Refresh Token을 무효화하여 로그아웃합니다.
                    클라이언트에서도 저장된 토큰을 삭제해주세요.
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "로그아웃 성공")
    })
    ResponseEntity<CommonResponse<Void>> logout(RefreshRequest request);
}
