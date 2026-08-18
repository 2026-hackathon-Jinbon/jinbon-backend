package com.jinbon.domain.video.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "영상 검증 결과 응답")
public record VideoVerifyResponse(
        @Schema(description = "구조화된 검증 판정") VerificationVerdict verdict,
        @Schema(description = "지각해시 평균 해밍 거리. 유사 일치에서만 제공", nullable = true)
        Double similarityDistance,
        @Schema(description = "진본 여부", example = "true") boolean authentic,
        @Schema(description = "영상 ID (미등록 시 null)", example = "1") Long videoId,
        @Schema(description = "영상 등록자 DID (레거시 필드명 issuerDid, 미등록 시 null)") String issuerDid,
        @Schema(description = "등록 시각 (미등록 시 null)") LocalDateTime registeredAt,
        @Schema(description = "블록체인 검증 성공 여부") boolean blockchainVerified,
        @Schema(description = "VC 검증 성공 여부") boolean vcVerified,
        @Schema(description = "VC 발급 문맥이 현재 영상·온체인 등록 정보와 결속됐는지 여부") boolean vcClaimsBound,
        @Schema(description = "영상 활성 상태") boolean active,
        @Schema(description = "검증 상세 메시지") String message,
        @Schema(description = "판정 해석 시 주의사항", nullable = true) String notice
) {

    public static VideoVerifyResponse notRegistered() {
        return new VideoVerifyResponse(VerificationVerdict.NOT_REGISTERED, null,
                false, null, null, null, false, false, false, false,
                "진본에 등록된 기록을 찾지 못했습니다.",
                "미등록은 영상이 조작되었다는 의미가 아닙니다.");
    }

    public static VideoVerifyResponse deactivated(Long videoId, String issuerDid, LocalDateTime registeredAt) {
        return new VideoVerifyResponse(VerificationVerdict.REGISTERED_BUT_REVOKED, null,
                false, videoId, issuerDid, registeredAt, false, false, false, false,
                "등록 후 비활성화된 영상입니다.", null);
    }
}
