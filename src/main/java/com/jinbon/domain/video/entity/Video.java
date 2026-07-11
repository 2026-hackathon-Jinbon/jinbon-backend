package com.jinbon.domain.video.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "videos")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Video {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String issuerDid;  // 등록한 공인의 DID

    @Column(nullable = false, columnDefinition = "TEXT")
    private String coarseHash;  // 거친해시

    @Column(nullable = false, columnDefinition = "TEXT")
    private String fineHash;  // 정교한해시

    @Column(nullable = false, columnDefinition = "TEXT")
    private String merkleRoot;  // 머클 루트

    @Column(columnDefinition = "TEXT")
    private String merklePath;  // 머클 패스 (JSON)

    private String blockNumber;  // 블록체인 기록 블록 번호

    private String txHash;  // 블록체인 트랜잭션 해시

    @Column(nullable = false)
    private String signature;  // 전자서명

    private Integer version;

    private LocalDateTime registeredAt;

    @Builder
    public Video(String title, String issuerDid, String coarseHash, String fineHash,
                 String merkleRoot, String merklePath, String blockNumber, String txHash,
                 String signature, Integer version) {
        this.title = title;
        this.issuerDid = issuerDid;
        this.coarseHash = coarseHash;
        this.fineHash = fineHash;
        this.merkleRoot = merkleRoot;
        this.merklePath = merklePath;
        this.blockNumber = blockNumber;
        this.txHash = txHash;
        this.signature = signature;
        this.version = version;
        this.registeredAt = LocalDateTime.now();
    }
}
