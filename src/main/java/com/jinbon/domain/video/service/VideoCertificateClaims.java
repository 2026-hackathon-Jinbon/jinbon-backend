package com.jinbon.domain.video.service;

import com.jinbon.domain.video.entity.Video;
import com.jinbon.global.config.BlockchainProperties;
import com.jinbon.global.config.OpenDidProperties;
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

    public Draft create(Video video) {
        requireConfirmedRegistration(video);

        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put(key("credentialType"), CREDENTIAL_TYPE);
        claims.put(key("assuranceType"), ASSURANCE_TYPE);
        claims.put(key("videoCommitment"), video.getMerkleRoot());
        claims.put(key("registrantDid"), video.getIssuerDid());
        claims.put(key("blockchainNetwork"), blockchainProperties.getNetwork());
        claims.put(key("chainId"), blockchainProperties.getChainId());
        claims.put(key("contractAddress"), blockchainProperties.getContractAddress());
        claims.put(key("transactionHash"), video.getTxHash());
        claims.put(key("blockNumber"), video.getBlockNumber());
        claims.put(key("registeredAt"), video.getRegisteredAt().toString());
        claims.put(key("videoTitle"), video.getTitle());
        claims.put(key("schemaVersion"), SCHEMA_VERSION);
        return new Draft(Map.copyOf(claims), canonicalValue(video));
    }

    private String key(String claimId) {
        return openDidProperties.claimKey(claimId);
    }

    private void requireConfirmedRegistration(Video video) {
        if (isBlank(video.getTxHash()) || isBlank(video.getBlockNumber())) {
            throw new IllegalStateException("Blockchain registration is not confirmed");
        }
        if (isBlank(blockchainProperties.getNetwork())
                || isBlank(blockchainProperties.getChainId())
                || isBlank(blockchainProperties.getContractAddress())) {
            throw new IllegalStateException("Blockchain evidence configuration is incomplete");
        }
    }

    private String canonicalValue(Video video) {
        return String.join("\n",
                Integer.toString(SCHEMA_VERSION),
                CREDENTIAL_TYPE,
                ASSURANCE_TYPE,
                video.getMerkleRoot(),
                video.getIssuerDid(),
                blockchainProperties.getNetwork(),
                blockchainProperties.getChainId(),
                blockchainProperties.getContractAddress(),
                video.getTxHash(),
                video.getBlockNumber(),
                video.getRegisteredAt().toString());
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
