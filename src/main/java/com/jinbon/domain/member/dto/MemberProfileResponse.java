package com.jinbon.domain.member.dto;

import com.jinbon.domain.member.entity.Member;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "내 프로필")
public record MemberProfileResponse(
        @Schema(description = "회원 ID") Long memberId,
        @Schema(description = "실명") String name,
        @Schema(description = "표시명 (없으면 null)", nullable = true) String displayName,
        @Schema(description = "검증 결과에 실제로 노출되는 이름") String publicName,
        @Schema(description = "Wallet DID") String userDid
) {
    public static MemberProfileResponse from(Member m) {
        return new MemberProfileResponse(m.getId(), m.getName(), m.getDisplayName(), m.getPublicName(), m.getUserDid());
    }
}
