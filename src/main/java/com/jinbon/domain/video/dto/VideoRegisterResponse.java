package com.jinbon.domain.video.dto;

import com.jinbon.domain.video.entity.Video;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "영상 등록 성공 응답")
public record VideoRegisterResponse(
        @Schema(description = "등록된 영상 ID", example = "1") Long videoId,
        @Schema(description = "영상 제목", example = "기자회견 원본 영상") String title,
        @Schema(description = "머클 루트 해시", example = "a1b2c3...") String merkleRoot,
        @Schema(description = "블록체인 트랜잭션 해시", example = "0xabc123...") String txHash,
        @Schema(description = "블록 번호", example = "12345") String blockNumber,
        @Schema(description = "Open DID VC ID. Wallet 발급 완료 전에는 null", example = "vc-abc123", nullable = true) String vcId,
        @Schema(description = "등록 시각") LocalDateTime registeredAt,
        @Schema(description = "동일 회원의 기존 등록 결과를 반환했는지 여부", example = "false") boolean alreadyRegistered,
        @Schema(description = "Wallet VC 발급에 사용할 Plan ID. 발급 준비가 되지 않았거나 이미 발급된 경우 null", example = "jinbon-video-vc-plan", nullable = true) String vcPlanId,
        @Schema(description = "Wallet VC 발급에 사용할 실제 Issuer DID. 발급 준비가 되지 않았거나 이미 발급된 경우 null", example = "did:omn:issuer123", nullable = true) String vcIssuerDid,
        @Schema(description = "Issuer-Initiated Wallet 발급 Offer ID", example = "offer-abc123", nullable = true) String vcOfferId
) {
    public static VideoRegisterResponse from(Video video, boolean alreadyRegistered,
                                             String vcPlanId, String vcIssuerDid, String vcOfferId) {
        return new VideoRegisterResponse(
                video.getId(), video.getTitle(), video.getMerkleRoot(),
                video.getTxHash(), video.getBlockNumber(), video.getVcId(),
                video.getRegisteredAt(), alreadyRegistered, vcPlanId, vcIssuerDid, vcOfferId
        );
    }
}
