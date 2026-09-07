package com.jinbon.infra.opendid;

import com.jinbon.global.config.OpenDidProperties;
import com.jinbon.domain.video.port.CredentialIssuancePort.Preparation;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class VcIssuanceServiceTest {

    @Test
    void disabledIntegrationDoesNotCallExternalSystems() {
        OpenDidIssuerClient issuerClient = mock(OpenDidIssuerClient.class);
        OpenDidTasClient tasClient = mock(OpenDidTasClient.class);
        OpenDidProperties properties = mock(OpenDidProperties.class);
        when(properties.isEnabled()).thenReturn(false);
        VcIssuanceService service = new VcIssuanceService(issuerClient, tasClient, properties);

        assertThat(service.prepare("did:omn:holder", Map.of())).isNull();
        service.syncHolder("did:omn:holder", Map.of());

        verifyNoInteractions(issuerClient, tasClient);
    }

    @Test
    void prepareMapsIssuerOfferToPortModel() {
        OpenDidIssuerClient issuerClient = mock(OpenDidIssuerClient.class);
        OpenDidTasClient tasClient = mock(OpenDidTasClient.class);
        OpenDidProperties properties = mock(OpenDidProperties.class);
        when(properties.isEnabled()).thenReturn(true);
        when(properties.getVcPlanId()).thenReturn("plan-1");
        when(tasClient.getHolderPii("did:omn:holder")).thenReturn("tasPii");
        when(issuerClient.createIssueOffer())
                .thenReturn(new OpenDidIssuerClient.IssueOffer("offer-1", "did:omn:issuer"));
        VcIssuanceService service = new VcIssuanceService(issuerClient, tasClient, properties);
        Map<String, Object> claims = Map.of("ns.claim", "value");

        Preparation result = service.prepare("did:omn:holder", claims);

        assertThat(result).isEqualTo(new Preparation("plan-1", "did:omn:issuer", "offer-1"));
        verify(issuerClient).prepareHolder("did:omn:holder", "tasPii", claims);
    }

    @Test
    void syncHolderPiiUsesPiiStoredByTas() {
        OpenDidIssuerClient issuerClient = mock(OpenDidIssuerClient.class);
        OpenDidTasClient tasClient = mock(OpenDidTasClient.class);
        OpenDidProperties properties = mock(OpenDidProperties.class);
        when(properties.isEnabled()).thenReturn(true);
        when(tasClient.getHolderPii("did:omn:holder")).thenReturn("tasPii");
        VcIssuanceService service = new VcIssuanceService(issuerClient, tasClient, properties);
        Map<String, Object> claims = Map.of("ns.claim", "value");

        service.syncHolder("did:omn:holder", claims);

        verify(issuerClient).prepareHolder("did:omn:holder", "tasPii", claims);
    }
}
