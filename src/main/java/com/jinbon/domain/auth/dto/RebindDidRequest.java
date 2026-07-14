package com.jinbon.domain.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record RebindDidRequest(
        @NotBlank String didRebindToken,
        @NotBlank String did
) {}
