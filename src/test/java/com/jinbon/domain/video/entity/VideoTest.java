package com.jinbon.domain.video.entity;

import com.jinbon.global.error.BusinessException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VideoTest {

    @Test
    void completesVcOnlyWithThePreparedOffer() {
        Video video = video();
        video.markVcPending("offer-1", "plan-1", "did:omn:issuer");

        video.completeVcIssuance("vc-1", "offer-1");

        assertThat(video.getVcId()).isEqualTo("vc-1");
        assertThat(video.getVcIssuanceStatus()).isEqualTo(VcIssuanceStatus.ISSUED);
    }

    @Test
    void rejectsVcFromAnotherOffer() {
        Video video = video();
        video.markVcPending("offer-1", "plan-1", "did:omn:issuer");

        assertThatThrownBy(() -> video.completeVcIssuance("vc-1", "offer-2"))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo("D005");
    }

    private Video video() {
        return Video.create("title", "did:omn:holder", 1L,
                "perceptual", "fine", "root", "path", null, null, "signature", 1);
    }
}
