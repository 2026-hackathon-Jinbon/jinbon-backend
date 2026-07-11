package com.jinbon.domain.auth.controller;

import com.jinbon.domain.auth.dto.AuthResponse;
import com.jinbon.domain.auth.dto.VerifyRequest;
import com.jinbon.domain.auth.service.AuthService;
import com.jinbon.global.common.CommonResponse;
import com.jinbon.infra.omnione.dto.OacxAppResponse;
import com.jinbon.infra.omnione.dto.OacxTokenResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
    public ResponseEntity<CommonResponse<AuthResponse>> verifyApp(@RequestBody VerifyRequest request) {
        return ResponseEntity.ok(CommonResponse.success(authService.verifyAppAndLogin(request)));
    }
}
