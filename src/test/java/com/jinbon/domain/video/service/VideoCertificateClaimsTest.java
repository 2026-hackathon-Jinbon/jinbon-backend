package com.jinbon.domain.video.service;

import com.jinbon.domain.video.entity.Video;
import com.jinbon.domain.video.port.CredentialVerificationPort.VerificationResult;
import com.jinbon.domain.video.port.VideoLedgerPort;
import com.jinbon.global.config.BlockchainProperties;
import com.jinbon.global.config.OpenDidProperties;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VideoCertificateClaimsTest {

    private final VideoCertificateClaims claims = new VideoCertificateClaims(
            new OpenDidProperties(true, "issuer", "plan", "ns-jinbon-video-01"),
            new BlockchainProperties("omnione", "100", "rpc", "0xcontract",
                    "wallet", "keystore", "password", "token"),
            mock(VideoLedgerPort.class)
    );

    @Test
    void createsClaimsMatchingTheRegisteredNineItemNamespace() {
        Map<String, Object> result = claims.create(registeredVideo()).claims();

        assertThat(result).containsOnlyKeys(
                "ns-jinbon-video-01.credentialType",
                "ns-jinbon-video-01.videoCommitment",
                "ns-jinbon-video-01.registrantDid",
                "ns-jinbon-video-01.chainId",
                "ns-jinbon-video-01.contractAddress",
                "ns-jinbon-video-01.transactionHash",
                "ns-jinbon-video-01.blockNumber",
                "ns-jinbon-video-01.registeredAt",
                "ns-jinbon-video-01.schemaVersion");
        assertThat(result.get("ns-jinbon-video-01.schemaVersion")).isEqualTo("1");
    }

    @Test
    void readsChainIdFromRpcWhenItIsNotConfigured() {
        VideoLedgerPort chainClient = mock(VideoLedgerPort.class);
        when(chainClient.getChainId()).thenReturn("100");
        VideoCertificateClaims claimsWithoutConfiguredChainId = new VideoCertificateClaims(
                new OpenDidProperties(true, "issuer", "plan", "ns-jinbon-video-01"),
                new BlockchainProperties("omnione", "", "rpc", "0xcontract",
                        "wallet", "keystore", "password", "token"),
                chainClient);

        assertThat(claimsWithoutConfiguredChainId.create(registeredVideo()).claims())
                .containsEntry("ns-jinbon-video-01.chainId", "100");
    }

    @Test
    void matchesTheOnChainEvidenceCapturedForIssuance() {
        Video video = registeredVideo();
        String snapshot = claims.create(video).snapshotHash("did:omn:issuer");
        video.markVcPending("offer", "plan", "did:omn:issuer", snapshot,
                VideoCertificateClaims.SCHEMA_VERSION, VideoCertificateClaims.ASSURANCE_TYPE);

        assertThat(claims.matchesSnapshot(video)).isTrue();
    }

    @Test
    void rejectsChangedBlockchainEvidence() {
        Video video = registeredVideo();
        String snapshot = claims.create(video).snapshotHash("did:omn:issuer");
        video.markVcPending("offer", "plan", "did:omn:issuer", snapshot,
                VideoCertificateClaims.SCHEMA_VERSION, VideoCertificateClaims.ASSURANCE_TYPE);

        video.recordBlockchain("0x20", "0xdifferent");

        assertThat(claims.matchesSnapshot(video)).isFalse();
    }

    @Test
    void matchesVerifiedCredentialToVideoAndIssuerContext() {
        Video video = registeredVideo();
        String issuerDid = "did:omn:issuer";
        video.markVcPending("offer", "plan", issuerDid,
                claims.create(video).snapshotHash(issuerDid),
                VideoCertificateClaims.SCHEMA_VERSION, VideoCertificateClaims.ASSURANCE_TYPE);

        VerificationResult result = new VerificationResult(
                com.jinbon.domain.video.port.CredentialVerificationPort.Status.VERIFIED,
                issuerDid, video.getIssuerDid(), claims.create(video).claims());

        assertThat(claims.matchesCredential(video, result)).isTrue();
    }

    @Test
    void rejectsCredentialFromAnotherVideoOrHolder() {
        Video video = registeredVideo();
        String issuerDid = "did:omn:issuer";
        video.markVcPending("offer", "plan", issuerDid,
                claims.create(video).snapshotHash(issuerDid),
                VideoCertificateClaims.SCHEMA_VERSION, VideoCertificateClaims.ASSURANCE_TYPE);

        VerificationResult result = new VerificationResult(
                com.jinbon.domain.video.port.CredentialVerificationPort.Status.VERIFIED,
                issuerDid, "did:omn:another-holder", claims.create(video).claims());

        assertThat(claims.matchesCredential(video, result)).isFalse();
    }

    @Test
    void matchesSnapshotAfterDatabaseTruncatesSubMicrosecondPrecision() {
        Video video = registeredVideo();
        String snapshot = claims.create(video).snapshotHash("did:omn:issuer");
        video.markVcPending("offer", "plan", "did:omn:issuer", snapshot,
                VideoCertificateClaims.SCHEMA_VERSION, VideoCertificateClaims.ASSURANCE_TYPE);

        // DB timestamp(6)는 마이크로초까지만 저장하므로, 완료 요청에서 재조회한
        // 엔티티의 registeredAt은 나노초가 잘려 있다.
        setRegisteredAt(video, video.getRegisteredAt().truncatedTo(ChronoUnit.MICROS));

        assertThat(claims.matchesSnapshot(video)).isTrue();
    }

    private Video registeredVideo() {
        Video video = Video.create("title", "did:omn:holder", 1L,
                "perceptual", null, null, "fine", "root", "path",
                "0x10", "0xtx", "signature", 1);
        // 나노초가 남아 있는 상태를 강제해 절삭 전후 차이를 검증할 수 있게 한다.
        setRegisteredAt(video, LocalDateTime.of(2026, 9, 17, 3, 14, 37, 535_155_123));
        return video;
    }

    private static void setRegisteredAt(Video video, LocalDateTime value) {
        try {
            Field field = Video.class.getDeclaredField("registeredAt");
            field.setAccessible(true);
            field.set(video, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to set registeredAt", e);
        }
    }
}
