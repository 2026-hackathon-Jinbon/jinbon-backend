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
    private String issuerDid;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String perceptualHash;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String fineHash;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String merkleRoot;

    @Column(columnDefinition = "TEXT")
    private String merklePath;

    private String blockNumber;

    private String txHash;

    @Column(nullable = false)
    private String signature;

    private Integer version;

    private String vcId;

    @Column(nullable = false)
    private boolean active = true;

    private LocalDateTime registeredAt;

    private LocalDateTime deactivatedAt;

    @Builder
    private Video(String title, String issuerDid, String perceptualHash, String fineHash,
                  String merkleRoot, String merklePath, String blockNumber, String txHash,
                  String signature, Integer version, String vcId) {
        this.title = title;
        this.issuerDid = issuerDid;
        this.perceptualHash = perceptualHash;
        this.fineHash = fineHash;
        this.merkleRoot = merkleRoot;
        this.merklePath = merklePath;
        this.blockNumber = blockNumber;
        this.txHash = txHash;
        this.signature = signature;
        this.version = version;
        this.vcId = vcId;
        this.active = true;
        this.registeredAt = LocalDateTime.now();
    }

    public void deactivate() {
        this.active = false;
        this.deactivatedAt = LocalDateTime.now();
    }
}
