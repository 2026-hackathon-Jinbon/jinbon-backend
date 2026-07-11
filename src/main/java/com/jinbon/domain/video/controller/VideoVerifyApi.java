package com.jinbon.domain.video.controller;

import com.jinbon.domain.video.dto.UrlVerifyRequest;
import com.jinbon.domain.video.dto.VideoVerifyResponse;
import com.jinbon.global.common.CommonResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;

@Tag(name = "영상 검증", description = """
        인증 불필요 — 영상 진본 여부 검증 API

        ## 검증 방식
        ### 1. URL 검증 (`POST /api/verify/url`) — 권장
        - 유튜브, 인스타, 틱톡 등 영상 URL을 전송하면 서버가 다운로드 후 전체 프레임 분석
        - 가장 정확한 검증 (밀도 높은 프레임 비교)

        ### 2. 파일 업로드 검증 (`POST /api/verify`)
        - 영상 파일을 직접 업로드하여 검증
        - SHA-256 정확 매칭 → 실패 시 pHash 유사도 검색
        """)
public interface VideoVerifyApi {

    @Operation(summary = "영상 검증 (파일 업로드)", description = "영상 파일을 업로드하여 진본 여부를 검증합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "검증 완료"),
            @ApiResponse(responseCode = "500", description = "영상 처리 실패")
    })
    ResponseEntity<CommonResponse<VideoVerifyResponse>> verify(
            @Parameter(description = "검증할 영상 파일", required = true) MultipartFile file);

    @Operation(summary = "영상 검증 (URL 기반)", description = """
            유튜브, 인스타, 틱톡 등 영상 URL을 전송하면 서버가 영상을 다운로드하여 검증합니다.
            서버가 전체 영상을 분석하므로 가장 정확한 검증이 가능합니다.
            다운로드 후 해시 계산이 완료되면 영상 파일은 즉시 삭제됩니다.
            """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "검증 완료"),
            @ApiResponse(responseCode = "400", description = "영상 다운로드 실패 (잘못된 URL 또는 지원하지 않는 플랫폼)")
    })
    ResponseEntity<CommonResponse<VideoVerifyResponse>> verifyByUrl(UrlVerifyRequest request);
}
