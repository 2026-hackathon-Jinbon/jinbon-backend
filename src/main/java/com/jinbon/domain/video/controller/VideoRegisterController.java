package com.jinbon.domain.video.controller;

import com.jinbon.domain.video.dto.VideoDetailResponse;
import com.jinbon.domain.video.dto.VideoRegisterResponse;
import com.jinbon.domain.video.dto.CompleteVideoVcRequest;
import com.jinbon.domain.video.service.VideoRegisterService;
import com.jinbon.global.common.CommonResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import jakarta.validation.Valid;

/**
 * 영상 등록/관리 API 컨트롤러.
 * ISSUER 권한이 필요하며, JWT 인증된 사용자만 접근할 수 있다.
 */
@RestController
@RequestMapping("/api/videos")
@RequiredArgsConstructor
public class VideoRegisterController implements VideoRegisterApi {

    private final VideoRegisterService videoRegisterService;

    @PostMapping
    @Override
    public ResponseEntity<CommonResponse<VideoRegisterResponse>> register(
            @RequestPart("file") MultipartFile file,
            @RequestParam("title") String title,
            Authentication authentication
    ) {
        Long memberId = Long.parseLong(authentication.getName());
        return ResponseEntity.ok(CommonResponse.success(videoRegisterService.register(file, title, memberId)));
    }

    @GetMapping
    @Override
    public ResponseEntity<CommonResponse<Page<VideoDetailResponse>>> getMyVideos(
            @PageableDefault Pageable pageable,
            Authentication authentication
    ) {
        Long memberId = Long.parseLong(authentication.getName());
        return ResponseEntity.ok(CommonResponse.success(videoRegisterService.getMyVideos(memberId, pageable)));
    }

    @GetMapping("/{videoId}")
    @Override
    public ResponseEntity<CommonResponse<VideoDetailResponse>> getVideoDetail(
            @PathVariable Long videoId,
            Authentication authentication
    ) {
        Long memberId = Long.parseLong(authentication.getName());
        return ResponseEntity.ok(CommonResponse.success(videoRegisterService.getVideoDetail(videoId, memberId)));
    }

    @PatchMapping("/{videoId}/deactivate")
    @Override
    public ResponseEntity<CommonResponse<Void>> deactivate(
            @PathVariable Long videoId,
            Authentication authentication
    ) {
        Long memberId = Long.parseLong(authentication.getName());
        videoRegisterService.deactivate(videoId, memberId);
        return ResponseEntity.ok(CommonResponse.success(null));
    }

    @PostMapping("/{videoId}/vc/complete")
    @Override
    public ResponseEntity<CommonResponse<Void>> completeVc(
            @PathVariable Long videoId,
            @Valid @RequestBody CompleteVideoVcRequest request,
            Authentication authentication
    ) {
        Long memberId = Long.parseLong(authentication.getName());
        videoRegisterService.completeVcIssuance(videoId, memberId, request.vcId());
        return ResponseEntity.ok(CommonResponse.success(null));
    }
}
