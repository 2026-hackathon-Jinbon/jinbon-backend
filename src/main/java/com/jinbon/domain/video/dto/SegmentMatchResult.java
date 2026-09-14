package com.jinbon.domain.video.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 세그먼트 지문 비교 결과.
 *
 * 제출 영상의 고정 간격 세그먼트 해시를 원본 세그먼트와 슬라이딩 비교하여
 * 커버리지, 시간 순서, 최적 오프셋, 불일치 구간을 계산한 결과이다.
 *
 * <ul>
 *   <li>{@code coverage} — 제출 영상 세그먼트 중 원본에 대응하는 비율 (0.0~1.0)</li>
 *   <li>{@code orderPreserved} — 일치 세그먼트들의 원본 인덱스가 단조 증가하는지 여부</li>
 *   <li>{@code bestOffsetMs} — 원본 기준 최적 시간 오프셋 (ms)</li>
 *   <li>{@code matchedStartMs / matchedEndMs} — 원본에서 대응하는 구간의 시작·끝 (ms)</li>
 *   <li>{@code unmatchedRanges} — 원본에 대응하지 않는 불일치 구간 목록</li>
 * </ul>
 */
@Schema(description = "세그먼트 지문 비교 결과")
public record SegmentMatchResult(
        @Schema(description = "제출 영상 중 원본에 대응하는 비율 (0.0~1.0)", example = "0.95")
        double coverage,
        @Schema(description = "시간 순서 보존 여부")
        boolean orderPreserved,
        @Schema(description = "원본 기준 최적 시간 오프셋 (ms)", example = "15000")
        long bestOffsetMs,
        @Schema(description = "원본에서 대응 시작 시각 (ms)", example = "15000")
        long matchedStartMs,
        @Schema(description = "원본에서 대응 끝 시각 (ms)", example = "45000")
        long matchedEndMs,
        @Schema(description = "일치 세그먼트 수")
        int matchedSegments,
        @Schema(description = "제출 영상 전체 세그먼트 수")
        int totalQuerySegments,
        @Schema(description = "원본 전체 세그먼트 수")
        int totalRefSegments,
        @Schema(description = "원본에 대응하지 않는 불일치 구간 목록")
        List<GapRange> unmatchedRanges
) {
    /** 세그먼트 비교가 불가능한 경우 (원본에 세그먼트 지문 없음 등) */
    public static SegmentMatchResult unavailable() {
        return new SegmentMatchResult(0, false, 0, 0, 0, 0, 0, 0, List.of());
    }
}
