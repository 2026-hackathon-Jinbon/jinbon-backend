package com.jinbon.infra.opendid;

import com.jinbon.global.config.OpenDidProperties;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VcIssuanceServiceTest {

    @Test
    void syncHolderPiiUsesPiiStoredByTas() {
        OpenDidIssuerClient issuerClient = mock(OpenDidIssuerClient.class);
        OpenDidTasClient tasClient = mock(OpenDidTasClient.class);
        OpenDidProperties properties = mock(OpenDidProperties.class);
        when(properties.isEnabled()).thenReturn(true);
        when(tasClient.getHolderPii("did:omn:holder")).thenReturn("tasPii");
        VcIssuanceService service = new VcIssuanceService(issuerClient, tasClient, properties);
        Map<String, Object> claims = Map.of("ns.claim", "value");

        service.syncHolderPii("did:omn:holder", claims);

        verify(issuerClient).prepareHolder("did:omn:holder", "tasPii", claims);
    }
}
