package com.jinbon.domain.video.service;

import com.jinbon.domain.video.entity.Video;
import com.jinbon.global.config.BlockchainProperties;
import com.jinbon.global.config.OpenDidProperties;
import com.jinbon.infra.blockchain.OmniOneChainClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/** 온체인 영상 등록 사실을 증명하는 VC의 클레임과 결속 해시를 생성한다. */
@Component
@RequiredArgsConstructor
public class VideoCertificateClaims {

    public static final String CREDENTIAL_TYPE = "VideoRegistrationCredential";
    public static final String ASSURANCE_TYPE = "BLOCKCHAIN_REGISTRATION";
    public static final int SCHEMA_VERSION = 1;

    private final OpenDidProperties openDidProperties;
    private final BlockchainProperties blockchainProperties;
    private final OmniOneChainClient omniOneChainClient;

    public Draft create(Video video) {
        requireConfirmedRegistration(video);
        String chainId = configuredChainId();

        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put(key("credentialType"), CREDENTIAL_TYPE);
        claims.put(key("videoCommitment"), video.getMerkleRoot());
        claims.put(key("registrantDid"), video.getIssuerDid());
        claims.put(key("chainId"), chainId);
        claims.put(key("contractAddress"), blockchainProperties.getContractAddress());
        claims.put(key("transactionHash"), video.getTxHash());
        claims.put(key("blockNumber"), video.getBlockNumber());
        claims.put(key("registeredAt"), video.getRegisteredAt().toString());
        claims.put(key("schemaVersion"), Integer.toString(SCHEMA_VERSION));
        return new Draft(Map.copyOf(claims), canonicalValue(video, chainId));
    }

    private String key(String claimId) {
        return openDidProperties.claimKey(claimId);
    }

    private void requireConfirmedRegistration(Video video) {
        if (isBlank(video.getTxHash()) || isBlank(video.getBlockNumber())) {
            throw new IllegalStateException("Blockchain registration is not confirmed");
        }
        if (isBlank(blockchainProperties.getContractAddress())) {
            throw new IllegalStateException("Blockchain evidence configuration is incomplete");
        }
    }

    private String configuredChainId() {
        return isBlank(blockchainProperties.getChainId())
                ? omniOneChainClient.getChainId()
                : blockchainProperties.getChainId();
    }

    private String canonicalValue(Video video, String chainId) {
        return String.join("\n",
                Integer.toString(SCHEMA_VERSION),
                CREDENTIAL_TYPE,
                video.getMerkleRoot(),
                video.getIssuerDid(),
                chainId,
                blockchainProperties.getContractAddress(),
                video.getTxHash(),
                video.getBlockNumber(),
                video.getRegisteredAt().toString());
    }

    /** 발급 준비 당시 결속한 클레임과 현재 온체인 등록 정보가 동일한지 확인한다. */
    public boolean matchesSnapshot(Video video) {
        if (isBlank(video.getVcClaimSnapshotHash()) || isBlank(video.getVcIssuerDid())) {
            return false;
        }
        try {
            String current = create(video).snapshotHash(video.getVcIssuerDid());
            return MessageDigest.isEqual(
                    current.getBytes(StandardCharsets.UTF_8),
                    video.getVcClaimSnapshotHash().getBytes(StandardCharsets.UTF_8));
        } catch (IllegalStateException e) {
            return false;
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public record Draft(Map<String, Object> claims, String canonicalClaims) {
        public String snapshotHash(String credentialIssuerDid) {
            if (credentialIssuerDid == null || credentialIssuerDid.isBlank()) {
                throw new IllegalArgumentException("credentialIssuerDid must not be blank");
            }
            try {
                byte[] digest = MessageDigest.getInstance("SHA-256")
                        .digest((canonicalClaims + "\n" + credentialIssuerDid)
                                .getBytes(StandardCharsets.UTF_8));
                return HexFormat.of().formatHex(digest);
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256 is not available", e);
            }
        }
    }
}
