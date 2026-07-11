package com.jinbon.domain.video.dto;

import com.jinbon.domain.video.entity.Video;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "영상 상세 응답")
public record VideoDetailResponse(
        @Schema(description = "영상 ID", example = "1") Long videoId,
        @Schema(description = "영상 제목", example = "2026 기자회견 원본") String title,
        @Schema(description = "머클 루트 해시") String merkleRoot,
        @Schema(description = "블록체인 트랜잭션 해시") String txHash,
        @Schema(description = "블록 번호") String blockNumber,
        @Schema(description = "Open DID VC ID") String vcId,
        @Schema(description = "활성 상태") boolean active,
        @Schema(description = "등록 시각") LocalDateTime registeredAt,
        @Schema(description = "비활성화 시각") LocalDateTime deactivatedAt
) {
    public static VideoDetailResponse from(Video video) {
        return new VideoDetailResponse(
                video.getId(), video.getTitle(), video.getMerkleRoot(),
                video.getTxHash(), video.getBlockNumber(), video.getVcId(),
                video.isActive(), video.getRegisteredAt(), video.getDeactivatedAt()
        );
    }
}
