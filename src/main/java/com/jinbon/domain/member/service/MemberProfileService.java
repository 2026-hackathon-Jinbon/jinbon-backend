package com.jinbon.domain.member.service;

import com.jinbon.domain.member.dto.MemberProfileResponse;
import com.jinbon.domain.member.entity.Member;
import com.jinbon.domain.member.repository.MemberRepository;
import com.jinbon.global.error.BusinessException;
import com.jinbon.global.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 등록자 프로필 — 검증 결과에 노출되는 표시명 관리. */
@Service
@RequiredArgsConstructor
public class MemberProfileService {

    private final MemberRepository memberRepository;

    @Transactional(readOnly = true)
    public MemberProfileResponse getProfile(Long memberId) {
        return MemberProfileResponse.from(findMember(memberId));
    }

    @Transactional
    public MemberProfileResponse updateDisplayName(Long memberId, String displayName) {
        Member member = findMember(memberId);
        member.updateDisplayName(displayName);
        return MemberProfileResponse.from(member);
    }

    private Member findMember(Long memberId) {
        return memberRepository.findById(memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));
    }
}
