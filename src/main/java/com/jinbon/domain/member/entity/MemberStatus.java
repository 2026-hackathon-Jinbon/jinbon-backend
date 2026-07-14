package com.jinbon.domain.member.entity;

/**
 * 회원 상태.
 *
 * 상태 전이: PENDING → ACTIVE (회원가입 완료 시)
 * ACTIVE → SUSPENDED (관리자 정지) / WITHDRAWN (탈퇴)
 */
public enum MemberStatus {
    /** 본인확인 완료, DID 연결 대기 (회원가입 미완료) */
    PENDING,
    /** 정상 활성 상태 */
    ACTIVE,
    /** 관리자에 의해 정지됨 */
    SUSPENDED,
    /** 탈퇴 */
    WITHDRAWN
}
