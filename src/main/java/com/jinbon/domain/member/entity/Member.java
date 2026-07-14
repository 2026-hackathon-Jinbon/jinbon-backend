package com.jinbon.domain.member.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "members")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Member {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String ci;  // OmniOne CX에서 받은 CI (연계정보)

    @Column(unique = true)
    private String userDid;  // Open DID 식별자

    @Column(nullable = false)
    private String name;

    private String birth;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MemberRole role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MemberStatus status;

    private LocalDateTime didRegisteredAt;

    private LocalDateTime joinedAt;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @Builder
    public Member(String ci, String userDid, String name, String birth, MemberRole role, MemberStatus status) {
        this.ci = ci;
        this.userDid = userDid;
        this.name = name;
        this.birth = birth;
        this.role = role;
        this.status = status == null ? MemberStatus.PENDING : status;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public void updateDid(String userDid) {
        this.userDid = userDid;
        this.role = MemberRole.ISSUER;
        this.status = MemberStatus.ACTIVE;
        this.didRegisteredAt = LocalDateTime.now();
        this.joinedAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public void rebindDid(String userDid) {
        this.userDid = userDid;
        this.didRegisteredAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public void promoteToIssuer() {
        if (this.role != MemberRole.ISSUER) {
            this.role = MemberRole.ISSUER;
            this.updatedAt = LocalDateTime.now();
        }
    }
}
