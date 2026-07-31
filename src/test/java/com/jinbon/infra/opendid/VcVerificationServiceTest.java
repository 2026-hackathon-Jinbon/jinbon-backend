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
        OpenDidVerifierClient client = mock(OpenDidVerifierClient.class);
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
        OpenDidVerifierClient client = mock(OpenDidVerifierClient.class);
        OpenDidProperties properties = mock(OpenDidProperties.class);
        when(properties.isEnabled()).thenReturn(true);
        doThrow(new RuntimeException("connection refused"))
                .when(client).getVcStatus("vc-1");

        VcVerificationService service = new VcVerificationService(client, properties);

        assertThat(service.verifyStatus("vc-1"))
                .isEqualTo(VcVerificationService.VerificationStatus.UNAVAILABLE);
    }
}
