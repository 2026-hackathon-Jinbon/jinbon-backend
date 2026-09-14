package com.jinbon.domain.member.controller;

import com.jinbon.domain.member.dto.MemberProfileResponse;
import com.jinbon.domain.member.dto.UpdateDisplayNameRequest;
import com.jinbon.domain.member.service.MemberProfileService;
import com.jinbon.global.common.CommonResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** 등록자 프로필 API. JWT 인증된 회원만 접근할 수 있다. */
@Tag(name = "등록자 프로필", description = "검증 결과에 노출되는 등록자 표시명을 관리합니다.")
@SecurityRequirement(name = "Bearer Token")
@RestController
@RequestMapping("/api/members/me")
@RequiredArgsConstructor
public class MemberProfileController {

    private final MemberProfileService memberProfileService;

    @Operation(summary = "내 프로필 조회")
    @GetMapping
    public ResponseEntity<CommonResponse<MemberProfileResponse>> getProfile(Authentication authentication) {
        return ResponseEntity.ok(CommonResponse.success(memberProfileService.getProfile(memberId(authentication))));
    }

    @Operation(summary = "표시명 변경", description = "검증 결과에 노출할 이름(기관명·직함)을 설정합니다. 비우면 실명으로 표시됩니다.")
    @PatchMapping("/display-name")
    public ResponseEntity<CommonResponse<MemberProfileResponse>> updateDisplayName(
            @Valid @RequestBody UpdateDisplayNameRequest request,
            Authentication authentication) {
        return ResponseEntity.ok(CommonResponse.success(
                memberProfileService.updateDisplayName(memberId(authentication), request.displayName())));
    }

    private Long memberId(Authentication authentication) {
        return Long.parseLong(authentication.getName());
    }
}
