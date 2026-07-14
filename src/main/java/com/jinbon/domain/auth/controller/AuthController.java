package com.jinbon.domain.auth.controller;

import com.jinbon.domain.auth.dto.AuthResponse;
import com.jinbon.domain.auth.dto.RefreshRequest;
import com.jinbon.domain.auth.dto.RebindDidRequest;
import com.jinbon.domain.auth.dto.VerifyRequest;
import com.jinbon.domain.auth.service.AuthService;
import com.jinbon.global.common.CommonResponse;
import com.jinbon.infra.omnione.dto.OacxAppResponse;
import com.jinbon.infra.omnione.dto.OacxTokenResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Operation;

/**
 * 인증 API 컨트롤러.
 * 모바일 신분증 기반 로그인, 토큰 갱신, 로그아웃, DID 재바인딩을 처리한다.
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController implements AuthApi {

    private final AuthService authService;

    @PostMapping("/token")
    @Override
    public ResponseEntity<CommonResponse<OacxTokenResponse>> createToken() {
        return ResponseEntity.ok(CommonResponse.success(authService.createOacxToken()));
    }

    @PostMapping("/app/request")
    @Override
    public ResponseEntity<CommonResponse<OacxAppResponse>> requestApp(
            @RequestParam String provider,
            @RequestParam String token,
            @RequestParam String txId) {
        return ResponseEntity.ok(CommonResponse.success(authService.requestApp(provider, token, txId)));
    }

    @PostMapping("/app/verify")
    @Override
    public ResponseEntity<CommonResponse<AuthResponse>> verifyApp(@Valid @RequestBody VerifyRequest request) {
        return ResponseEntity.ok(CommonResponse.success(authService.verifyAppAndLogin(request)));
    }

    @PostMapping("/did/rebind")
    @Operation(summary = "앱 재설치 후 Wallet DID 재연결", description = "모바일 신분증 로그인 응답의 단기 didRebindToken과 새 Wallet DID를 사용해 기존 회원의 DID를 안전하게 교체합니다.")
    public ResponseEntity<CommonResponse<AuthResponse>> rebindDid(@Valid @RequestBody RebindDidRequest request) {
        return ResponseEntity.ok(CommonResponse.success(
                authService.rebindDid(request.didRebindToken(), request.did())));
    }

    @PostMapping("/refresh")
    @Override
    public ResponseEntity<CommonResponse<AuthResponse>> refresh(@Valid @RequestBody RefreshRequest request) {
        return ResponseEntity.ok(CommonResponse.success(authService.refresh(request.refreshToken())));
    }

    @PostMapping("/logout")
    @Override
    public ResponseEntity<CommonResponse<Void>> logout(@Valid @RequestBody RefreshRequest request) {
        authService.logout(request.refreshToken());
        return ResponseEntity.ok(CommonResponse.success(null));
    }
}
