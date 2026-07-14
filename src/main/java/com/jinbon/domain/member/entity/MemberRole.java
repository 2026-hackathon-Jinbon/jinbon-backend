package com.jinbon.domain.member.entity;

/**
 * 회원 역할.
 *
 * 가입 완료 시 ISSUER로 자동 승격되며,
 * 레거시 USER 회원도 로그인 시 자동으로 ISSUER로 보정된다.
 */
public enum MemberRole {
    /** 일반 사용자 (레거시, 영상 등록 불가) */
    USER,
    /** 공인 등록자 (영상 등록 + VC 발급 권한) */
    ISSUER
}
