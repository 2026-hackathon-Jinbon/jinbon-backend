package com.jinbon.domain.video.controller;

import com.jinbon.domain.video.dto.VideoDetailResponse;
import com.jinbon.domain.video.dto.VideoRegisterResponse;
import com.jinbon.global.common.CommonResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.multipart.MultipartFile;

@Tag(name = "영상 관리", description = """
        ISSUER 전용 — 영상 등록, 조회, 비활성화 API

        ## 등록 흐름
        ```
        1. 영상 파일 + 제목 업로드 (multipart/form-data)
        2. coarseHash + fineHash 생성
        3. 머클트리 생성 → merkleRoot 계산
        4. 전자서명 생성 → 블록체인 기록
        5. DB 저장 후 결과 반환
        ```

        ## 비활성화
        - 블록체인에 비활성화 트랜잭션 기록 후 DB 상태 변경
        - 비활성화된 영상은 검증 시 "등록 취소된 영상"으로 응답
        """)
@SecurityRequirement(name = "Bearer Authentication")
public interface VideoRegisterApi {

    @Operation(summary = "영상 등록", description = "영상 파일을 업로드하여 진본 등록합니다. 해시 추출 후 파일은 저장하지 않습니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "영상 등록 성공"),
            @ApiResponse(responseCode = "403", description = "ISSUER 권한 없음"),
            @ApiResponse(responseCode = "409", description = "이미 등록된 영상")
    })
    ResponseEntity<CommonResponse<VideoRegisterResponse>> register(
            @Parameter(description = "영상 파일", required = true) MultipartFile file,
            @Parameter(description = "영상 제목", required = true, example = "2026 기자회견 원본") String title,
            @Parameter(hidden = true) Authentication authentication);

    @Operation(summary = "내 영상 목록 조회", description = "내가 등록한 영상 목록을 최신순으로 조회합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공")
    })
    ResponseEntity<CommonResponse<Page<VideoDetailResponse>>> getMyVideos(
            Pageable pageable,
            @Parameter(hidden = true) Authentication authentication);

    @Operation(summary = "영상 상세 조회", description = "등록된 영상의 상세 정보를 조회합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "404", description = "영상 없음"),
            @ApiResponse(responseCode = "403", description = "본인 영상이 아님")
    })
    ResponseEntity<CommonResponse<VideoDetailResponse>> getVideoDetail(
            @Parameter(description = "영상 ID", required = true) Long videoId,
            @Parameter(hidden = true) Authentication authentication);

    @Operation(summary = "영상 비활성화", description = "등록된 영상을 비활성화합니다. 블록체인에도 비활성화 트랜잭션이 기록됩니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "비활성화 성공"),
            @ApiResponse(responseCode = "404", description = "영상 없음"),
            @ApiResponse(responseCode = "403", description = "본인 영상이 아님"),
            @ApiResponse(responseCode = "400", description = "이미 비활성화된 영상")
    })
    ResponseEntity<CommonResponse<Void>> deactivate(
            @Parameter(description = "영상 ID", required = true) Long videoId,
            @Parameter(hidden = true) Authentication authentication);
}
