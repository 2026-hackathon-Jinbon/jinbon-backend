package com.jinbon.domain.video.service;

import com.jinbon.domain.member.entity.Member;
import com.jinbon.domain.member.entity.MemberRole;
import com.jinbon.domain.member.entity.MemberStatus;
import com.jinbon.domain.member.repository.MemberRepository;
import com.jinbon.domain.video.dto.*;
import com.jinbon.domain.video.entity.Video;
import com.jinbon.domain.video.port.*;
import com.jinbon.domain.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockMultipartFile;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class VideoVerifyServiceTest {
    private final VideoRepository videos = mock(VideoRepository.class);
    private final MemberRepository members = mock(MemberRepository.class);
    private final HashService hashes = mock(HashService.class);
    private final PerceptualHashService phash = spy(new PerceptualHashService());
    private final SignatureService signatures = mock(SignatureService.class);
    private final VideoLedgerPort ledger = mock(VideoLedgerPort.class);
    private final CredentialVerificationPort credentials = mock(CredentialVerificationPort.class);
    private final VideoCertificateClaims claims = mock(VideoCertificateClaims.class);
    private final VerificationCache cache = mock(VerificationCache.class);
    private final VideoFingerprintService segFp = spy(new VideoFingerprintService());
    private final AudioFingerprintService audioFp = spy(new AudioFingerprintService());
    private final MediaFingerprintService mediaFp = new MediaFingerprintService(phash, segFp, audioFp);
    private final VideoContentMatchService contentMatcher = new VideoContentMatchService(videos, phash, segFp, audioFp);
    private final VideoVerifyService service = new VideoVerifyService(videos, members, hashes, mediaFp, contentMatcher,
            signatures, ledger, credentials, claims, mock(VideoSourcePort.class), cache);
    private final Member registrant = Member.create("h1:ci", "holder", "홍길동", "19800101",
            MemberRole.ISSUER, MemberStatus.ACTIVE);
    private final MockMultipartFile file = new MockMultipartFile("file", new byte[]{1});
    private final String fineHash = "a".repeat(64);
    private final String fingerprint = "v2|2000000|0000000000000000,ffffffffffffffff";

    // 세그먼트·음성 지문이 모두 있는 영상 (진본 판정 가능)
    private final String segFpStr = "seg-v1|2000000|1000|0000000000000000,ffffffffffffffff";
    private final String audioFpStr = "aud-v1|2000000|1000|00000000000000ff,ffffffffffffff00";
    private final Video video = Video.create("original", "holder", 1L, fingerprint,
            segFpStr, audioFpStr, fineHash, "root", "path", "0x1", "tx", "signature", 1);

    @BeforeEach void setUp() throws Exception {
        video.markVcPending("offer", "plan", "issuer", "snapshot", 1, "BLOCKCHAIN_REGISTRATION");
        video.completeVcIssuance("vc", "offer", "credential");
        var verified = new CredentialVerificationPort.VerificationResult(
                CredentialVerificationPort.Status.VERIFIED, "issuer", "holder", Map.of());
        when(hashes.generateFineHash(any())).thenReturn(fineHash);
        doReturn(fingerprint).when(phash).generateFingerprint(file);
        doReturn(segFpStr).when(segFp).generate(file);
        doReturn(audioFpStr).when(audioFp).generate(file);
        when(videos.findByActiveTrue()).thenReturn(List.of(video));
        when(ledger.getRecord("root")).thenReturn(new VideoLedgerPort.Record(true, true, "holder", "signature"));
        when(signatures.sign(anyString())).thenReturn("signature");
        when(credentials.verify("vc", "credential")).thenReturn(verified);
        when(claims.matchesSnapshot(video)).thenReturn(true);
        when(claims.matchesCredential(video, verified)).thenReturn(true);
        registrant.updateDisplayName("기획재정부 대변인실");
        when(members.findById(1L)).thenReturn(Optional.of(registrant));
    }

    @Test void exactFileWithValidEvidenceIsAuthentic() {
        when(videos.findByFineHash(fineHash)).thenReturn(Optional.of(video));
        var result = service.verify(file);
        assertThat(result.verdict()).isEqualTo(VerificationVerdict.EXACT_MATCH);
        assertThat(result.authentic()).isTrue();
        assertThat(result.displayStatus()).isEqualTo(DisplayStatus.AUTHENTICATED);
        assertThat(result.registrantName()).isEqualTo("기획재정부 대변인실");
    }

    @Test void videoAndAudioMatchIsAuthentic() {
        var result = service.verify(file);
        assertThat(result.verdict()).isEqualTo(VerificationVerdict.SIMILAR_MATCH);
        assertThat(result.authentic()).isTrue();
        assertThat(result.displayStatus()).isEqualTo(DisplayStatus.AUTHENTICATED);
        assertThat(result.blockchainVerified()).isTrue();
        assertThat(result.vcVerified()).isTrue();
        assertThat(result.registrantName()).isEqualTo("기획재정부 대변인실");
        assertThat(result.audioMatch()).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(ints = {10, 20, 100})
    void oneUnmatchedAudioSegmentIsNotAuthentic(int segmentCount) {
        // 90%, 95%, 99% 일치여도 불일치 음성 구간이 하나 있으면 인증하지 않는다.
        var audioComparison = new SegmentMatchResult(
                (double) (segmentCount - 1) / segmentCount, true, 0,
                0, segmentCount * 1000L, segmentCount - 1, segmentCount, segmentCount,
                List.of(new GapRange(5000, 6000)));
        doReturn(audioComparison).when(audioFp).compare(audioFpStr, audioFpStr);

        var result = service.verify(file);

        assertThat(result.segmentMatch().coverage()).isEqualTo(1.0);
        assertThat(result.audioMatch().unmatchedRanges()).hasSize(1);
        assertThat(result.notice()).contains("음성 비교 기준을 충족하지 못해 진본으로 인증하지 않습니다.");
        assertThat(result.audioMatch().bestOffsetMs()).isEqualTo(result.segmentMatch().bestOffsetMs());
        assertThat(result.authentic()).isFalse();
        assertThat(result.verdict()).isEqualTo(VerificationVerdict.CONTENT_SIMILAR);
        assertThat(result.displayStatus()).isEqualTo(DisplayStatus.CONTENT_SIMILAR);
    }

    @Test void minorAudioFingerprintDifferencesWithinEverySegmentAreStillAuthentic() throws Exception {
        // 각 구간의 해밍 거리가 4인 경우: 구간 내부의 기존 허용 오차는 유지한다.
        doReturn("aud-v1|2000000|1000|000000000000000f,fffffffffffffff0")
                .when(audioFp).generate(file);

        var result = service.verify(file);

        assertThat(result.audioMatch().coverage()).isEqualTo(1.0);
        assertThat(result.audioMatch().unmatchedRanges()).isEmpty();
        assertThat(result.authentic()).isTrue();
    }

    @Test void matchingOriginalSegmentWithAudioIsAuthentic() throws Exception {
        doReturn("v2|1000000|0000000000000000").when(phash).generateFingerprint(file);
        doReturn("seg-v1|1000000|1000|0000000000000000").when(segFp).generate(file);
        doReturn("aud-v1|1000000|1000|00000000000000ff").when(audioFp).generate(file);

        var result = service.verify(file);

        assertThat(result.verdict()).isEqualTo(VerificationVerdict.SIMILAR_MATCH);
        assertThat(result.authentic()).isTrue();
        assertThat(result.message()).contains("원본");
        assertThat(result.segmentMatch().matchedStartMs()).isEqualTo(0L);
    }

    @Test void videoMatchWithoutAudioIsContentSimilar() throws Exception {
        // 음성 지문 생성 실패 → null
        doThrow(new java.io.IOException("no audio")).when(audioFp).generate(file);
        var result = service.verify(file);
        assertThat(result.verdict()).isEqualTo(VerificationVerdict.CONTENT_SIMILAR);
        assertThat(result.authentic()).isFalse();
        assertThat(result.displayStatus()).isEqualTo(DisplayStatus.CONTENT_SIMILAR);
        assertThat(result.notice()).contains("음성 비교 정보가 부족해").doesNotContain("음성 비교 기준을 충족하지 못해");
    }

    @Test void matchingVideoAndAudioFromDifferentSourceTimesIsNotAuthentic() throws Exception {
        doReturn("v2|1000000|0000000000000000").when(phash).generateFingerprint(file);
        doReturn("seg-v1|1000000|1000|0000000000000000").when(segFp).generate(file);
        doReturn("aud-v1|1000000|1000|ffffffffffffff00").when(audioFp).generate(file);

        var result = service.verify(file);

        assertThat(result.segmentMatch().coverage()).isEqualTo(1.0);
        assertThat(result.audioMatch().coverage()).isEqualTo(1.0);
        assertThat(result.segmentMatch().bestOffsetMs()).isEqualTo(0);
        assertThat(result.audioMatch().bestOffsetMs()).isEqualTo(1000);
        assertThat(result.authentic()).isFalse();
        assertThat(result.verdict()).isEqualTo(VerificationVerdict.CONTENT_SIMILAR);
        assertThat(result.notice()).contains("영상과 음성의 원본 대응 시점이 달라").doesNotContain("보류");
    }

    @Test void matchingVideoAndAudioAtSameNonzeroSourceTimeIsAuthentic() throws Exception {
        doReturn("v2|1000000|ffffffffffffffff").when(phash).generateFingerprint(file);
        doReturn("seg-v1|1000000|1000|ffffffffffffffff").when(segFp).generate(file);
        doReturn("aud-v1|1000000|1000|ffffffffffffff00").when(audioFp).generate(file);

        var result = service.verify(file);

        assertThat(result.segmentMatch().bestOffsetMs()).isEqualTo(1000);
        assertThat(result.audioMatch().bestOffsetMs()).isEqualTo(1000);
        assertThat(result.authentic()).isTrue();
        assertThat(result.verdict()).isEqualTo(VerificationVerdict.SIMILAR_MATCH);
    }

    @Test void videoMatchWithAudioMismatchIsContentSimilar() throws Exception {
        // 제출 영상의 음성이 원본과 완전히 다름 (해밍 거리 큼)
        doReturn("aud-v1|2000000|1000|aaaaaaaaaaaaaaaa,5555555555555555").when(audioFp).generate(file);
        var result = service.verify(file);
        assertThat(result.verdict()).isEqualTo(VerificationVerdict.CONTENT_SIMILAR);
        assertThat(result.authentic()).isFalse();
        assertThat(result.notice()).contains("영상 구간 일치율 100.0% (2/2)", "음성 구간 일치율 0.0% (0/2)",
                "음성 비교 기준을 충족하지 못해 진본으로 인증하지 않습니다.")
                .doesNotContain("보류", "원본 대응 시점이 달라");
    }

    @Test void perceptualMatchStillRequiresValidCredential() {
        when(credentials.verify("vc", "credential")).thenReturn(CredentialVerificationPort.VerificationResult.invalid());
        var result = service.verify(file);
        assertThat(result.verdict()).isEqualTo(VerificationVerdict.CERTIFICATE_INVALID);
        assertThat(result.authentic()).isFalse();
        assertThat(result.displayStatus()).isEqualTo(DisplayStatus.NOT_AUTHENTICATED);
    }

    @Test void registrantNameFallsBackToRealNameWithoutDisplayName() {
        registrant.updateDisplayName(null);
        var result = service.verify(file);
        assertThat(result.registrantName()).isEqualTo("홍길동");
    }

    @Test void partialPHashWithLowSegmentCoverageIsPartialMatch() throws Exception {
        doReturn("v2|1000000|0000000000000000").when(phash).generateFingerprint(file);
        // 세그먼트·음성 지문도 없는 경우 → PARTIAL_MATCH
        doThrow(new java.io.IOException("fail")).when(segFp).generate(file);
        doThrow(new java.io.IOException("fail")).when(audioFp).generate(file);
        var result = service.verify(file);
        assertThat(result.verdict()).isEqualTo(VerificationVerdict.PARTIAL_MATCH);
        assertThat(result.authentic()).isFalse();
        assertThat(result.displayStatus()).isEqualTo(DisplayStatus.CONTENT_SIMILAR);
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
