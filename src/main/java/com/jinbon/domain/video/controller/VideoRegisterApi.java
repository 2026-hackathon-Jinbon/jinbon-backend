package com.jinbon.domain.video.controller;

import com.jinbon.domain.video.dto.VideoDetailResponse;
import com.jinbon.domain.video.dto.VideoRegisterResponse;
import com.jinbon.domain.video.dto.CompleteVideoVcRequest;
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
        ISSUER 전용 — 영상 등록, 조회, Wallet VC 연결, 비활성화 API

        ## 등록 흐름
        ```
        1. 영상 파일 + 제목 업로드 (multipart/form-data)
        2. SHA-256 fineHash + 프레임 기반 perceptualHash 생성
        3. 머클트리 생성 → merkleRoot 계산
        4. 전자서명 생성 → 블록체인 기록
        5. DB 저장
        6. Open DID Issuer에 Holder DID와 영상 Claim 등록 및 Offer 생성
        7. Wallet 발급에 필요한 vcPlanId, vcIssuerDid, vcOfferId 반환
        8. 앱 Wallet에서 VC 수령 후 발급 완료 API 호출
        ```

        ## 비활성화
        - 블록체인에 비활성화 트랜잭션 기록 후 DB 상태 변경
        - 비활성화된 영상은 검증 시 "등록 취소된 영상"으로 응답
        """)
@SecurityRequirement(name = "Bearer Token")
public interface VideoRegisterApi {

    @Operation(summary = "영상 등록 및 Wallet VC 발급 준비", description = """
            영상 파일의 해시와 블록체인 증적을 등록하며 원본 파일 자체는 서버에 저장하지 않습니다.

            등록 성공 후 VC 발급 준비가 완료되면 응답의 `vcPlanId`, `vcIssuerDid`, `vcOfferId`를 사용해 앱 Wallet에서 사용자 동의와 PIN 인증을 거쳐 VC를 발급합니다. VC 발급은 영상 등록과 별도 단계이므로 취소하거나 실패해도 영상 등록은 유지됩니다.

            같은 회원의 동일 파일 재요청은 기존 등록 결과를 반환하고 `alreadyRegistered=true`로 표시합니다. 다른 회원이 이미 등록한 동일 파일은 차단합니다.
            """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "신규 등록 또는 동일 회원의 기존 등록 결과 반환"),
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

    @Operation(summary = "Wallet VC 발급 완료", description = """
            앱 Wallet이 VC 발급과 로컬 저장을 완료한 뒤 발급된 vcId를 영상에 연결합니다.
            본인이 등록한 영상에만 호출할 수 있으며, 이 API가 성공하면 VC 발급 상태는 `ISSUED`가 됩니다.
            """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "VC 연결 성공"),
            @ApiResponse(responseCode = "400", description = "VC 발급 준비 전 호출 또는 잘못된 vcId"),
            @ApiResponse(responseCode = "403", description = "본인 영상이 아님"),
            @ApiResponse(responseCode = "404", description = "영상 없음")
    })
    ResponseEntity<CommonResponse<Void>> completeVc(
            @Parameter(description = "영상 ID", required = true) Long videoId,
            @Parameter(description = "Wallet에서 발급된 VC 정보", required = true) CompleteVideoVcRequest request,
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
