package com.jinbon.domain.video.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "영상 검증 결과 응답")
public record VideoVerifyResponse(
        @Schema(description = "내부 검증 판정 (로그·디버깅용)") VerificationVerdict verdict,
        @Schema(description = "클라이언트 표시용 상태") DisplayStatus displayStatus,
        @Schema(description = "지각해시 평균 해밍 거리. 유사 일치에서만 제공", nullable = true)
        Double similarityDistance,
        @Schema(description = "진본 여부 — 원본 파일 일치 또는 지각해시 동일 내용이면서 블록체인·VC 검증 통과", example = "true") boolean authentic,
        @Schema(description = "영상 ID (미등록 시 null)", example = "1") Long videoId,
        @Schema(description = "영상 등록자 DID (레거시 필드명 issuerDid, 미등록 시 null)") String issuerDid,
        @Schema(description = "등록 시각 (미등록 시 null)") LocalDateTime registeredAt,
        @Schema(description = "등록자 표시명 (기관명 등). 표시명이 없으면 실명. 미등록 시 null", nullable = true) String registrantName,
        @Schema(description = "블록체인 검증 성공 여부") boolean blockchainVerified,
        @Schema(description = "VC 검증 성공 여부") boolean vcVerified,
        @Schema(description = "VC 발급 문맥이 현재 영상·온체인 등록 정보와 결속됐는지 여부") boolean vcClaimsBound,
        @Schema(description = "영상 활성 상태") boolean active,
        @Schema(description = "검증 상세 메시지") String message,
        @Schema(description = "판정 해석 시 주의사항", nullable = true) String notice,
        @Schema(description = "세그먼트 지문 비교 결과. 세그먼트 비교가 수행된 경우에만 제공", nullable = true)
        SegmentMatchResult segmentMatch
) {

    /** 세그먼트 비교 없는 기본 팩토리 (기존 호출 호환) */
    public static VideoVerifyResponse of(VerificationVerdict verdict, Double similarityDistance,
                                          boolean authentic, Long videoId, String issuerDid,
                                          LocalDateTime registeredAt, boolean blockchainVerified,
                                          boolean vcVerified, boolean vcClaimsBound, boolean active,
                                          String message, String notice) {
        return of(verdict, similarityDistance, authentic, videoId, issuerDid, registeredAt, null,
                blockchainVerified, vcVerified, vcClaimsBound, active, message, notice, null);
    }

    /** 세그먼트 비교 없는 팩토리 (등록자명 포함) */
    public static VideoVerifyResponse of(VerificationVerdict verdict, Double similarityDistance,
                                          boolean authentic, Long videoId, String issuerDid,
                                          LocalDateTime registeredAt, String registrantName,
                                          boolean blockchainVerified, boolean vcVerified,
                                          boolean vcClaimsBound, boolean active,
                                          String message, String notice) {
        return of(verdict, similarityDistance, authentic, videoId, issuerDid, registeredAt,
                registrantName, blockchainVerified, vcVerified, vcClaimsBound, active,
                message, notice, null);
    }

    /** 세그먼트 비교 결과 포함 팩토리 */
    public static VideoVerifyResponse of(VerificationVerdict verdict, Double similarityDistance,
                                          boolean authentic, Long videoId, String issuerDid,
                                          LocalDateTime registeredAt, String registrantName,
                                          boolean blockchainVerified, boolean vcVerified,
                                          boolean vcClaimsBound, boolean active,
                                          String message, String notice,
                                          SegmentMatchResult segmentMatch) {
        return new VideoVerifyResponse(verdict, verdict.toDisplayStatus(), similarityDistance,
                authentic, videoId, issuerDid, registeredAt, registrantName,
                blockchainVerified, vcVerified, vcClaimsBound, active, message, notice,
                segmentMatch);
    }

    public static VideoVerifyResponse notRegistered() {
        return of(VerificationVerdict.NOT_REGISTERED, null,
                false, null, null, null, false, false, false, false,
                "진본에 등록된 기록을 찾지 못했습니다.",
                "미등록은 영상이 조작되었다는 의미가 아닙니다.");
    }

    public static VideoVerifyResponse deactivated(Long videoId, String issuerDid, LocalDateTime registeredAt) {
        return of(VerificationVerdict.REGISTERED_BUT_REVOKED, null,
                false, videoId, issuerDid, registeredAt, false, false, false, false,
                "등록 후 비활성화된 영상입니다.", null);
    }
}
