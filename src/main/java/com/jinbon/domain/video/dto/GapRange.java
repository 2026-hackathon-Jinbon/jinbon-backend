package com.jinbon.domain.video.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 원본에 대응하지 않는 불일치 구간.
 *
 * 제출 영상의 세그먼트 중 원본과 매칭되지 않는 연속 구간을
 * 밀리초 단위 시작·끝 시각으로 나타낸다.
 */
@Schema(description = "원본에 대응하지 않는 불일치 구간 (밀리초)")
public record GapRange(
        @Schema(description = "불일치 시작 시각 (ms)", example = "3000") long startMs,
        @Schema(description = "불일치 끝 시각 (ms)", example = "5000") long endMs
) {
    public long durationMs() {
        return endMs - startMs;
    }
}
