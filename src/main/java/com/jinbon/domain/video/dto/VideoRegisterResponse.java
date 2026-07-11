package com.jinbon.domain.video.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "영상 등록 성공 응답")
public record VideoRegisterResponse(
        @Schema(description = "등록된 영상 ID", example = "1") Long videoId,
        @Schema(description = "영상 제목", example = "2026 기자회견 원본") String title,
        @Schema(description = "머클 루트 해시", example = "a1b2c3...") String merkleRoot,
        @Schema(description = "블록체인 트랜잭션 해시", example = "0xabc123...") String txHash,
        @Schema(description = "블록 번호", example = "12345") String blockNumber,
        @Schema(description = "Open DID VC ID", example = "vc-abc123") String vcId,
        @Schema(description = "등록 시각") LocalDateTime registeredAt
) {}
