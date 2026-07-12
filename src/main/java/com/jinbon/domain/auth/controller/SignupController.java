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
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/signup")
@RequiredArgsConstructor
public class SignupController {

    private final AuthService authService;

    @PostMapping("/token")
    public ResponseEntity<CommonResponse<OacxTokenResponse>> createToken() {
        return ResponseEntity.ok(CommonResponse.success(authService.createOacxToken()));
    }

    @PostMapping("/app/request")
    public ResponseEntity<CommonResponse<OacxAppResponse>> requestApp(
            @RequestParam String provider, @RequestParam String token, @RequestParam String txId) {
        return ResponseEntity.ok(CommonResponse.success(authService.requestApp(provider, token, txId)));
    }

    @PostMapping("/app/verify")
    public ResponseEntity<CommonResponse<SignupIdentityResponse>> verify(@RequestBody VerifyRequest request) {
        return ResponseEntity.ok(CommonResponse.success(authService.verifyAppForSignup(request)));
    }

    @PostMapping("/did/complete")
    public ResponseEntity<CommonResponse<AuthResponse>> complete(@Valid @RequestBody CompleteSignupRequest request) {
        return ResponseEntity.ok(CommonResponse.success(
                authService.completeSignup(request.signupToken(), request.did())));
    }
}
