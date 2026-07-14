package com.jinbon.domain.video.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "Wallet VC 발급 완료 요청")
public record CompleteVideoVcRequest(
        @Schema(description = "Wallet에서 발급·저장된 VC 식별자", example = "vc-abc123",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        @Size(max = 500, message = "vcId must be 500 characters or less")
        String vcId
) {
}
