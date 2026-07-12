package com.jinbon.domain.auth.dto;

public record SignupIdentityResponse(
        String signupToken,
        Long memberId,
        String name,
        String status
) {}
