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
        String vcId,
        @Schema(description = "영상 등록 응답에서 받은 발급 Offer ID",
                example = "offer-abc123", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        @Size(max = 500, message = "offerId must be 500 characters or less")
        String offerId,
        @Schema(description = "Wallet에 저장된 서명 포함 VC JSON 원문",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        @Size(max = 100000, message = "credential must be 100000 characters or less")
        String credential
) {
}
