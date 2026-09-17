package com.jinbon.domain.video.service;

import com.jinbon.domain.video.entity.Video;
import com.jinbon.domain.video.port.CredentialVerificationPort;
import com.jinbon.domain.video.port.CredentialVerificationPort.VerificationResult;
import com.jinbon.domain.video.port.VideoLedgerPort;
import com.jinbon.global.config.BlockchainProperties;
import com.jinbon.global.config.OpenDidProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** 온체인 영상 등록 사실을 증명하는 VC의 클레임과 결속 해시를 생성한다. */
@Slf4j
@Component
@RequiredArgsConstructor
public class VideoCertificateClaims {

    public static final String CREDENTIAL_TYPE = "VideoRegistrationCredential";
    public static final String ASSURANCE_TYPE = "BLOCKCHAIN_REGISTRATION";
    public static final int SCHEMA_VERSION = 1;

    private final OpenDidProperties openDidProperties;
    private final BlockchainProperties blockchainProperties;
    private final VideoLedgerPort videoLedgerPort;

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
        claims.put(key("registeredAt"), registeredAt(video));
        claims.put(key("schemaVersion"), Integer.toString(SCHEMA_VERSION));
        return new Draft(Map.copyOf(claims), canonicalValue(video, chainId));
    }

    /**
     * 등록 시각을 마이크로초로 잘라 문자열화한다.
     *
     * DB timestamp(6)가 나노초를 버리기 때문에, 발급 준비 때의 메모리 값과
     * 완료 때 DB에서 읽은 값이 그대로는 다른 문자열이 되어 스냅샷 대조가 실패한다.
     */
    private String registeredAt(Video video) {
        return video.getRegisteredAt().truncatedTo(ChronoUnit.MICROS).toString();
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
                ? videoLedgerPort.getChainId()
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
                registeredAt(video));
    }

    /** 발급 준비 당시 결속한 클레임과 현재 온체인 등록 정보가 동일한지 확인한다. */
    public boolean matchesSnapshot(Video video) {
        if (isBlank(video.getVcClaimSnapshotHash()) || isBlank(video.getVcIssuerDid())) {
            return false;
        }
        try {
            Draft draft = create(video);
            String current = draft.snapshotHash(video.getVcIssuerDid());
            boolean matches = MessageDigest.isEqual(
                    current.getBytes(StandardCharsets.UTF_8),
                    video.getVcClaimSnapshotHash().getBytes(StandardCharsets.UTF_8));
            if (!matches) {
                log.warn("Snapshot recomputation differs - videoId={}, stored={}, current={}, canonical=[{}], credentialIssuerDid={}",
                        video.getId(), video.getVcClaimSnapshotHash(), current,
                        draft.canonicalClaims().replace("\n", "|"), video.getVcIssuerDid());
            }
            return matches;
        } catch (IllegalStateException e) {
            return false;
        }
    }

    /** 검증된 VC의 issuer, subject, 핵심 claim이 영상의 발급 문맥과 일치하는지 확인한다. */
    public boolean matchesCredential(Video video, VerificationResult result) {
        if (result == null || result.status() != CredentialVerificationPort.Status.VERIFIED
                || !Objects.equals(video.getVcIssuerDid(), result.issuerDid())
                || !Objects.equals(video.getIssuerDid(), result.subjectDid())) {
            return false;
        }
        try {
            return create(video).claims().entrySet().stream()
                    .allMatch(entry -> Objects.equals(
                            String.valueOf(entry.getValue()),
                            String.valueOf(result.claims().get(entry.getKey()))));
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
