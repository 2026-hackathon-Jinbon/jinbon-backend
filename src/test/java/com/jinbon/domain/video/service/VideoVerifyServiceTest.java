package com.jinbon.domain.video.service;

import com.jinbon.domain.video.dto.*;
import com.jinbon.domain.video.entity.Video;
import com.jinbon.domain.video.port.*;
import com.jinbon.domain.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class VideoVerifyServiceTest {
    private final VideoRepository videos = mock(VideoRepository.class);
    private final HashService hashes = mock(HashService.class);
    private final PerceptualHashService phash = spy(new PerceptualHashService());
    private final SignatureService signatures = mock(SignatureService.class);
    private final VideoLedgerPort ledger = mock(VideoLedgerPort.class);
    private final CredentialVerificationPort credentials = mock(CredentialVerificationPort.class);
    private final VideoCertificateClaims claims = mock(VideoCertificateClaims.class);
    private final VerificationCache cache = mock(VerificationCache.class);
    private final VideoVerifyService service = new VideoVerifyService(videos, hashes, phash, signatures,
            ledger, credentials, claims, mock(VideoSourcePort.class), cache);
    private final MockMultipartFile file = new MockMultipartFile("file", new byte[]{1});
    private final String fineHash = "a".repeat(64);
    private final String fingerprint = "v2|2000000|0000000000000000,ffffffffffffffff";
    private final Video video = Video.create("original", "holder", 1L, fingerprint,
            fineHash, "root", "path", "0x1", "tx", "signature", 1);

    @BeforeEach void setUp() throws Exception {
        video.markVcPending("offer", "plan", "issuer", "snapshot", 1, "BLOCKCHAIN_REGISTRATION");
        video.completeVcIssuance("vc", "offer", "credential");
        var verified = new CredentialVerificationPort.VerificationResult(
                CredentialVerificationPort.Status.VERIFIED, "issuer", "holder", Map.of());
        when(hashes.generateFineHash(any())).thenReturn(fineHash);
        doReturn(fingerprint).when(phash).generateFingerprint(file);
        when(videos.findByActiveTrue()).thenReturn(List.of(video));
        when(ledger.getRecord("root")).thenReturn(new VideoLedgerPort.Record(true, true, "holder", "signature"));
        when(signatures.sign(anyString())).thenReturn("signature");
        when(credentials.verify("vc", "credential")).thenReturn(verified);
        when(claims.matchesSnapshot(video)).thenReturn(true);
        when(claims.matchesCredential(video, verified)).thenReturn(true);
    }

    @Test void onlyExactFileWithValidEvidenceIsAuthentic() {
        when(videos.findByFineHash(fineHash)).thenReturn(Optional.of(video));
        var result = service.verify(file);
        assertThat(result.verdict()).isEqualTo(VerificationVerdict.EXACT_MATCH);
        assertThat(result.authentic()).isTrue();
        assertThat(result.displayStatus()).isEqualTo(DisplayStatus.AUTHENTICATED);
    }

    @Test void zeroPerceptualDistanceDoesNotCertifyOriginalBytes() {
        var result = service.verify(file);
        assertThat(result.verdict()).isEqualTo(VerificationVerdict.SIMILAR_MATCH);
        assertThat(result.authentic()).isFalse();
        assertThat(result.displayStatus()).isEqualTo(DisplayStatus.CONTENT_SIMILAR);
        assertThat(result.blockchainVerified()).isTrue();
        assertThat(result.vcVerified()).isTrue();
    }

    @Test void singleOriginalFrameIsOnlyPartial() throws Exception {
        doReturn("v2|1000000|0000000000000000").when(phash).generateFingerprint(file);
        var result = service.verify(file);
        assertThat(result.verdict()).isEqualTo(VerificationVerdict.PARTIAL_MATCH);
        assertThat(result.authentic()).isFalse();
        assertThat(result.displayStatus()).isEqualTo(DisplayStatus.PARTIAL_SIMILAR);
    }

    @Test void exactMatchStillRequiresValidCredential() {
        when(videos.findByFineHash(fineHash)).thenReturn(Optional.of(video));
        when(credentials.verify("vc", "credential")).thenReturn(CredentialVerificationPort.VerificationResult.invalid());
        var result = service.verify(file);
        assertThat(result.verdict()).isEqualTo(VerificationVerdict.CERTIFICATE_INVALID);
        assertThat(result.authentic()).isFalse();
    }

    @Test void externalFailureIsUnavailableRatherThanContentApproval() {
        when(ledger.getRecord("root")).thenThrow(new IllegalStateException("offline"));
        var result = service.verify(file);
        assertThat(result.verdict()).isEqualTo(VerificationVerdict.VERIFICATION_UNAVAILABLE);
        assertThat(result.authentic()).isFalse();
    }
}
