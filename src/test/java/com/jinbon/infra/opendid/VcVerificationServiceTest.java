package com.jinbon.infra.opendid;

import com.jinbon.global.config.OpenDidProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

class VcVerificationServiceTest {

    @Test
    void disabledIntegrationIsNotReportedAsVerified() {
        OpenDidIssuerClient client = mock(OpenDidIssuerClient.class);
        OpenDidProperties properties = mock(OpenDidProperties.class);
        when(properties.isEnabled()).thenReturn(false);

        VcVerificationService service = new VcVerificationService(client, properties);

        assertThat(service.verify("arbitrary-vc")).isFalse();
        assertThat(service.verifyStatus("arbitrary-vc"))
                .isEqualTo(VcVerificationService.VerificationStatus.DISABLED);
        verifyNoInteractions(client);
    }

    @Test
    void verifierFailureIsReportedAsUnavailable() {
        OpenDidIssuerClient client = mock(OpenDidIssuerClient.class);
        OpenDidProperties properties = mock(OpenDidProperties.class);
        when(properties.isEnabled()).thenReturn(true);
        doThrow(new RuntimeException("connection refused"))
                .when(client).getIssuedVcStatus("vc-1");

        VcVerificationService service = new VcVerificationService(client, properties);

        assertThat(service.verifyStatus("vc-1"))
                .isEqualTo(VcVerificationService.VerificationStatus.UNAVAILABLE);
    }

    @Test
    void activeIssuedVcIsVerified() {
        OpenDidIssuerClient client = mock(OpenDidIssuerClient.class);
        OpenDidProperties properties = mock(OpenDidProperties.class);
        when(properties.isEnabled()).thenReturn(true);
        when(client.getIssuedVcStatus("vc-1")).thenReturn("ACTIVE");

        VcVerificationService service = new VcVerificationService(client, properties);

        assertThat(service.verifyStatus("vc-1"))
                .isEqualTo(VcVerificationService.VerificationStatus.VERIFIED);
    }

    @Test
    void missingIssuedVcIsInvalid() {
        OpenDidIssuerClient client = mock(OpenDidIssuerClient.class);
        OpenDidProperties properties = mock(OpenDidProperties.class);
        when(properties.isEnabled()).thenReturn(true);

        VcVerificationService service = new VcVerificationService(client, properties);

        assertThat(service.verifyStatus("vc-1"))
                .isEqualTo(VcVerificationService.VerificationStatus.INVALID);
    }
}
