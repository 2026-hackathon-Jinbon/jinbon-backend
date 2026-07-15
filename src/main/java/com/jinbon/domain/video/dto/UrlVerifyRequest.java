package com.jinbon.domain.video.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "URL 기반 영상 검증 요청")
public record UrlVerifyRequest(
        @Schema(description = "영상 URL (YouTube, Instagram, TikTok 등)",
                example = "https://youtube.com/shorts/abc123")
        @NotBlank
        @Size(max = 2048)
        String url
) {}
