package com.jinbon.domain.member.entity;

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
 * 회원 엔티티.
 *
 * CI(연계정보)는 HMAC-SHA256 해시(h1: 접두사)로만 저장한다.
 * 평문 CI는 절대 DB에 저장하지 않으며, CiHashMigration이 레거시 데이터를 일괄 전환한다.
 * userDid는 Open DID Wallet에서 생성한 DID 식별자로 unique 제약이 설정되어 있다.
 */
@Entity
@Table(name = "members")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Member {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * CI 식별자 (HMAC-SHA256 해시, "h1:" 접두사 포함).
     * DB 컬럼명은 레거시 호환을 위해 "ci"를 유지하나, 실제 값은 항상 해시이다.
     */
    @Column(name = "ci", nullable = false, unique = true)
    private String ciHash;

    /** Open DID 식별자 (Wallet에서 생성, 앱 재설치 시 재바인딩 필요) */
    @Column(unique = true)
    private String userDid;

    /** 실명 (본인확인 시 추출) */
    @Column(nullable = false)
    private String name;

    /** 생년월일 */
    private String birth;

    /** 회원 역할 (USER: 일반, ISSUER: 영상 등록 권한) */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MemberRole role;

    /** 회원 상태 (PENDING → ACTIVE, SUSPENDED, WITHDRAWN) */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MemberStatus status;

    /** DID 최초 등록 또는 재바인딩 시각 */
    private LocalDateTime didRegisteredAt;

    /** 회원가입 완료 시각 */
    private LocalDateTime joinedAt;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /**
     * 회원을 생성한다.
     *
     * @param ciHash  HMAC-SHA256 해시 처리된 CI (평문 CI 전달 금지)
     * @param userDid Open DID 식별자 (회원가입 STEP 1에서는 null)
     * @param name    실명
     * @param birth   생년월일
     * @param role    역할
     * @param status  초기 상태 (null이면 PENDING)
     */
    public static Member create(String ciHash, String userDid, String name, String birth,
                                MemberRole role, MemberStatus status) {
        Member member = new Member();
        member.ciHash = ciHash;
        member.userDid = userDid;
        member.name = name;
        member.birth = birth;
        member.role = role;
        member.status = status == null ? MemberStatus.PENDING : status;
        member.createdAt = LocalDateTime.now();
        member.updatedAt = LocalDateTime.now();
        return member;
    }

    /**
     * 레거시 CI 원문을 HMAC 해시로 교체한다 (마이그레이션 전용).
     * CiHashMigration에서 호출하며, 트랜잭션 내에서 dirty-checking으로 반영된다.
     */
    public void migrateCiHash(String ciHash) {
        this.ciHash = ciHash;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 회원가입을 완료한다 (DID 연결 + ACTIVE 전환).
     * PENDING 상태에서만 호출 가능하다.
     */
    public void updateDid(String userDid) {
        this.userDid = userDid;
        this.role = MemberRole.ISSUER;
        this.status = MemberStatus.ACTIVE;
        this.didRegisteredAt = LocalDateTime.now();
        this.joinedAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    /** 앱 재설치 후 DID를 재바인딩한다 */
    public void rebindDid(String userDid) {
        this.userDid = userDid;
        this.didRegisteredAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    /** 레거시 USER 역할을 ISSUER로 승격한다 (가입 완료 회원 자동 보정) */
    public void promoteToIssuer() {
        if (this.role != MemberRole.ISSUER) {
            this.role = MemberRole.ISSUER;
            this.updatedAt = LocalDateTime.now();
        }
    }
}
