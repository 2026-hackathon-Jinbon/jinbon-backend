package com.jinbon.domain.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record CompleteSignupRequest(
        @NotBlank String signupToken,
        @NotBlank String did
) {}
