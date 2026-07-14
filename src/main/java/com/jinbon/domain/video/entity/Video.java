package com.jinbon.domain.video.entity;

import com.jinbon.global.error.BusinessException;
import com.jinbon.global.error.ErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 영상 엔티티.
 *
 * 영상의 해시, 블록체인 기록, VC 발급 상태 등을 관리한다.
 * fineHash(SHA-256)는 unique 제약으로 동일 파일의 중복 등록을 DB 레벨에서 방지한다.
 */
@Entity
@Table(name = "videos")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Video {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 영상 제목 */
    @Column(nullable = false)
    private String title;

    /** 등록자의 DID 식별자 */
    @Column(nullable = false)
    private String issuerDid;

    /** 등록자 회원 ID (레거시 데이터는 null일 수 있음) */
    private Long memberId;

    /** 지각해시 — DCT 기반 프레임별 fingerprint (재인코딩 영상 유사도 비교용) */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String perceptualHash;

    /** 정밀해시 — SHA-256 전체 파일 해시 (원본 동일성 확인용, unique 제약) */
    @Column(nullable = false, unique = true, columnDefinition = "TEXT")
    private String fineHash;

    /** 머클트리 루트 해시 — 블록체인 기록의 기준값 */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String merkleRoot;

    /** 머클트리 경로 — 검증 시 루트 재구성에 사용 */
    @Column(columnDefinition = "TEXT")
    private String merklePath;

    /** 블록체인 블록 번호 */
    private String blockNumber;

    /** 블록체인 트랜잭션 해시 */
    private String txHash;

    /** 전자서명 — issuerDid + merkleRoot를 HMAC-SHA256으로 서명한 값 */
    @Column(nullable = false)
    private String signature;

    /** 데이터 스키마 버전 */
    private Integer version;

    /** Verifiable Credential 식별자 (Open DID Issuer가 발급) */
    private String vcId;

    /** VC 발급 상태 (enum으로 관리하여 오타에 의한 버그 방지) */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private VcIssuanceStatus vcIssuanceStatus = VcIssuanceStatus.NOT_REQUESTED;

    /** 활성 상태 (비활성화 시 검증에서 제외) */
    @Column(nullable = false)
    private boolean active = true;

    /** 등록 시각 */
    private LocalDateTime registeredAt;

    /** 비활성화 시각 */
    private LocalDateTime deactivatedAt;

    /**
     * 영상 엔티티를 생성한다.
     * 초기 VC 발급 상태는 NOT_REQUESTED, 활성 상태는 true로 설정된다.
     */
    public static Video create(String title, String issuerDid, Long memberId,
                               String perceptualHash, String fineHash, String merkleRoot,
                               String merklePath, String blockNumber, String txHash,
                               String signature, Integer version) {
        Video video = new Video();
        video.title = title;
        video.issuerDid = issuerDid;
        video.memberId = memberId;
        video.perceptualHash = perceptualHash;
        video.fineHash = fineHash;
        video.merkleRoot = merkleRoot;
        video.merklePath = merklePath;
        video.blockNumber = blockNumber;
        video.txHash = txHash;
        video.signature = signature;
        video.version = version;
        video.active = true;
        video.registeredAt = LocalDateTime.now();
        video.vcIssuanceStatus = VcIssuanceStatus.NOT_REQUESTED;
        return video;
    }

    /** VC 발급 준비 상태로 전이한다 (NOT_REQUESTED → PENDING_WALLET) */
    public void markVcPending() {
        this.vcIssuanceStatus = VcIssuanceStatus.PENDING_WALLET;
    }

    /**
     * Wallet에서 VC 발급이 완료되었음을 기록한다.
     * 반드시 PENDING_WALLET 상태에서만 호출 가능하다.
     *
     * @throws BusinessException PENDING_WALLET 상태가 아닌 경우
     */
    public void completeVcIssuance(String vcId) {
        if (this.vcIssuanceStatus != VcIssuanceStatus.PENDING_WALLET) {
            throw new BusinessException(ErrorCode.VC_ISSUANCE_NOT_PREPARED);
        }
        this.vcId = vcId;
        this.vcIssuanceStatus = VcIssuanceStatus.ISSUED;
    }

    /** 영상을 비활성화한다 (검증 결과에서 '비활성화됨'으로 표시) */
    public void deactivate() {
        this.active = false;
        this.deactivatedAt = LocalDateTime.now();
    }
}
