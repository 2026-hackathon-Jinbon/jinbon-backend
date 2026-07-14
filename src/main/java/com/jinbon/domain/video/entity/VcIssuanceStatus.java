package com.jinbon.domain.video.entity;

/**
 * VC(Verifiable Credential) 발급 상태.
 *
 * 상태 전이: NOT_REQUESTED → PENDING_WALLET → ISSUED
 */
public enum VcIssuanceStatus {

    /** VC 발급 미요청 (영상 등록 직후 초기 상태) */
    NOT_REQUESTED,

    /** Issuer 서버에 발급 준비 완료, Wallet 앱의 수령 대기 중 */
    PENDING_WALLET,

    /** Wallet에서 VC 수령 완료 */
    ISSUED
}
