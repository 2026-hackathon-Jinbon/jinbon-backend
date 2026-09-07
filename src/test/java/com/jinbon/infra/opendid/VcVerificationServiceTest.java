package com.jinbon.infra.opendid;

import com.jinbon.global.config.OpenDidProperties;
import com.jinbon.domain.video.port.CredentialVerificationPort.Status;
import com.jinbon.domain.video.port.CredentialVerificationPort.VerificationResult;

import java.util.Map;
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

        assertThat(service.verify("arbitrary-vc").status()).isEqualTo(Status.DISABLED);
        verifyNoInteractions(client);
    }

    @Test
    void verifierFailureIsReportedAsUnavailable() {
        OpenDidIssuerClient client = mock(OpenDidIssuerClient.class);
        OpenDidProperties properties = mock(OpenDidProperties.class);
        when(properties.isEnabled()).thenReturn(true);
        doThrow(new RuntimeException("connection refused")).when(client).getIssuedVc("vc-1");

        VcVerificationService service = new VcVerificationService(client, properties);

        assertThat(service.verify("vc-1").status()).isEqualTo(Status.UNAVAILABLE);
    }

    @Test
    void activeIssuedVcIsVerified() {
        OpenDidIssuerClient client = mock(OpenDidIssuerClient.class);
        OpenDidProperties properties = mock(OpenDidProperties.class);
        when(properties.isEnabled()).thenReturn(true);
        when(client.getIssuedVc("vc-1")).thenReturn(new OpenDidIssuerClient.IssuedVc(
                "ACTIVE", "did:omn:issuer", "did:omn:holder", Map.of("claim", "value")));

        VcVerificationService service = new VcVerificationService(client, properties);

        VerificationResult result = service.verify("vc-1");
        assertThat(result.status()).isEqualTo(Status.VERIFIED);
        assertThat(result.issuerDid()).isEqualTo("did:omn:issuer");
        assertThat(result.subjectDid()).isEqualTo("did:omn:holder");
    }

    @Test
    void missingIssuedVcIsInvalid() {
        OpenDidIssuerClient client = mock(OpenDidIssuerClient.class);
        OpenDidProperties properties = mock(OpenDidProperties.class);
        when(properties.isEnabled()).thenReturn(true);

        VcVerificationService service = new VcVerificationService(client, properties);

        assertThat(service.verify("vc-1").status()).isEqualTo(Status.INVALID);
    }
}
