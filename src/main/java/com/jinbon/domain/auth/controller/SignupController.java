package com.jinbon.domain.auth.controller;

import com.jinbon.domain.auth.dto.AuthResponse;
import com.jinbon.domain.auth.dto.CompleteSignupRequest;
import com.jinbon.domain.auth.dto.SignupIdentityResponse;
import com.jinbon.domain.auth.dto.VerifyRequest;
import com.jinbon.domain.auth.service.AuthService;
import com.jinbon.global.common.CommonResponse;
import com.jinbon.infra.omnione.dto.OacxAppResponse;
import com.jinbon.infra.omnione.dto.OacxTokenResponse;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/signup")
@RequiredArgsConstructor
@Tag(name = "회원가입", description = "모바일 신분증 본인확인 후 Wallet DID를 연결하여 최초 1회 가입을 완료하는 API")
public class SignupController {

    private final AuthService authService;

    @PostMapping("/token")
    @Operation(summary = "[STEP 1] 회원가입용 OmniOne CX 토큰 발급")
    public ResponseEntity<CommonResponse<OacxTokenResponse>> createToken() {
        return ResponseEntity.ok(CommonResponse.success(authService.createOacxToken()));
    }

    @PostMapping("/app/request")
    @Operation(summary = "[STEP 2] 회원가입용 WebToApp 인증 요청", description = "모바일 신분증 앱을 호출할 딥링크와 cxId를 생성합니다.")
    public ResponseEntity<CommonResponse<OacxAppResponse>> requestApp(
            @RequestParam String provider, @RequestParam String token, @RequestParam String txId) {
        return ResponseEntity.ok(CommonResponse.success(authService.requestApp(provider, token, txId)));
    }

    @PostMapping("/app/verify")
    @Operation(summary = "[STEP 3] 본인확인 및 PENDING 회원 생성", description = "모바일 신분증 결과를 검증합니다. 이미 가입된 CI이면 중복 가입 오류를 반환하며, 성공 시 DID 연결에 사용할 단기 signupToken을 발급합니다.")
    public ResponseEntity<CommonResponse<SignupIdentityResponse>> verify(@Valid @RequestBody VerifyRequest request) {
        return ResponseEntity.ok(CommonResponse.success(authService.verifyAppForSignup(request)));
    }

    @PostMapping("/did/complete")
    @Operation(summary = "[STEP 4] Wallet DID 연결 및 회원가입 완료", description = "signupToken과 Wallet에서 생성·등록한 DID를 연결하고 회원을 ACTIVE/ISSUER로 전환한 뒤 JWT를 발급합니다.")
    public ResponseEntity<CommonResponse<AuthResponse>> complete(@Valid @RequestBody CompleteSignupRequest request) {
        return ResponseEntity.ok(CommonResponse.success(
                authService.completeSignup(request.signupToken(), request.did())));
    }
}
