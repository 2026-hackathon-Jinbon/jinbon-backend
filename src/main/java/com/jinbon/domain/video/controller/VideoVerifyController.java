package com.jinbon.domain.video.controller;

import com.jinbon.domain.video.dto.UrlVerifyRequest;
import com.jinbon.domain.video.dto.VideoVerifyResponse;
import com.jinbon.domain.video.service.VideoVerifyService;
import com.jinbon.global.common.CommonResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * 영상 검증 API (크롬 확장 등에서 호출 - 인증 불필요)
 */
@RestController
@RequestMapping("/api/verify")
@RequiredArgsConstructor
public class VideoVerifyController implements VideoVerifyApi {

    private final VideoVerifyService videoVerifyService;

    /** 영상 파일 업로드 기반 검증 */
    @PostMapping
    @Override
    public ResponseEntity<CommonResponse<VideoVerifyResponse>> verify(
            @RequestPart("file") MultipartFile file
    ) {
        return ResponseEntity.ok(CommonResponse.success(videoVerifyService.verify(file)));
    }

    /** URL 기반 검증 (서버가 영상 다운로드 후 전체 프레임 분석) */
    @PostMapping("/url")
    @Override
    public ResponseEntity<CommonResponse<VideoVerifyResponse>> verifyByUrl(
            @Valid @RequestBody UrlVerifyRequest request
    ) {
        return ResponseEntity.ok(CommonResponse.success(videoVerifyService.verifyByUrl(request.url())));
    }
}
