package com.jinbon.domain.video.service;

import com.jinbon.domain.video.entity.Video;
import com.jinbon.global.config.BlockchainProperties;
import com.jinbon.global.config.OpenDidProperties;
import com.jinbon.infra.blockchain.OmniOneChainClient;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VideoCertificateClaimsTest {

    private final VideoCertificateClaims claims = new VideoCertificateClaims(
            new OpenDidProperties(true, "issuer", "verifier", "plan",
                    "ns-jinbon-video-01", "did:omn:issuer"),
            new BlockchainProperties("omnione", "100", "rpc", "0xcontract",
                    "wallet", "keystore", "password", "token"),
            mock(OmniOneChainClient.class)
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
        OmniOneChainClient chainClient = mock(OmniOneChainClient.class);
        when(chainClient.getChainId()).thenReturn("100");
        VideoCertificateClaims claimsWithoutConfiguredChainId = new VideoCertificateClaims(
                new OpenDidProperties(true, "issuer", "verifier", "plan",
                        "ns-jinbon-video-01", "did:omn:issuer"),
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

    private Video registeredVideo() {
        return Video.create("title", "did:omn:holder", 1L,
                "perceptual", "fine", "root", "path",
                "0x10", "0xtx", "signature", 1);
    }
}
