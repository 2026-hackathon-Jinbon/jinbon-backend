package com.jinbon.domain.video.service;

import com.jinbon.domain.member.entity.*;
import com.jinbon.domain.member.repository.MemberRepository;
import com.jinbon.domain.video.entity.Video;
import com.jinbon.domain.video.port.*;
import com.jinbon.domain.video.repository.VideoRepository;
import com.jinbon.global.config.OpenDidProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class VideoRegisterServiceTest {
    @Test void matchingVisualFramesDoNotBlockRegistrationOfDifferentBytes() throws Exception {
        MemberRepository members = mock(MemberRepository.class);
        VideoRepository videos = mock(VideoRepository.class);
        HashService hashes = new HashService();
        SignatureService signatures = mock(SignatureService.class);
        VideoLedgerPort ledger = mock(VideoLedgerPort.class);
        String fingerprint = "v2|1000000|0000000000000000";
        Video existing = Video.create("existing", "another-holder", 2L, fingerprint,
                null, null, "different-file-hash", "old-root", "path", "block", "tx", "sig", 1);
        when(videos.findAll()).thenReturn(List.of(existing));
        when(members.findById(1L)).thenReturn(Optional.of(Member.create(
                "ci", "holder", "name", "birth", MemberRole.ISSUER, MemberStatus.ACTIVE)));
        MediaFingerprintService fingerprints = mock(MediaFingerprintService.class);
        when(fingerprints.generate(any(org.springframework.web.multipart.MultipartFile.class)))
                .thenReturn(new MediaFingerprintService.Fingerprints(fingerprint, null, null));
        when(signatures.sign(anyString())).thenReturn("sig");
        when(videos.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(ledger.register(anyString(), eq("holder"), eq("sig")))
                .thenReturn(new VideoLedgerPort.Registration("tx", "block"));
        when(ledger.getRecord(anyString())).thenReturn(new VideoLedgerPort.Record(true, true, "holder", "sig"));
        VideoRegisterService service = new VideoRegisterService(members, videos, hashes, fingerprints,
                signatures, ledger,
                mock(CredentialIssuancePort.class), mock(VideoCertificateClaims.class),
                mock(CredentialVerificationPort.class), mock(OpenDidProperties.class), mock(VideoVerifyService.class));

        var result = service.register(new MockMultipartFile("file", new byte[]{1, 2, 3}), "new", 1L);

        assertThat(result.alreadyRegistered()).isFalse();
        verify(ledger).register(anyString(), eq("holder"), eq("sig"));
        verify(videos).saveAndFlush(any());
    }
}
