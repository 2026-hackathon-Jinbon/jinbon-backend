package com.jinbon.domain.video.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "영상 검증 결과 응답")
public record VideoVerifyResponse(
        @Schema(description = "진본 여부", example = "true") boolean authentic,
        @Schema(description = "영상 ID (미등록 시 null)", example = "1") Long videoId,
        @Schema(description = "등록 발급자 DID (미등록 시 null)") String issuerDid,
        @Schema(description = "등록 시각 (미등록 시 null)") LocalDateTime registeredAt,
        @Schema(description = "블록체인 검증 성공 여부") boolean blockchainVerified,
        @Schema(description = "VC 검증 성공 여부") boolean vcVerified,
        @Schema(description = "영상 활성 상태") boolean active,
        @Schema(description = "검증 상세 메시지") String message
) {

    public static VideoVerifyResponse notRegistered() {
        return new VideoVerifyResponse(false, null, null, null, false, false, false,
                "Video is not registered.");
    }

    public static VideoVerifyResponse deactivated(Long videoId, String issuerDid, LocalDateTime registeredAt) {
        return new VideoVerifyResponse(false, videoId, issuerDid, registeredAt, false, false, false,
                "Video has been deactivated.");
    }
}
