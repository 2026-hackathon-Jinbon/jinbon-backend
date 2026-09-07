package com.jinbon.infra.opendid;

import com.jinbon.global.config.OpenDidProperties;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OpenDidIssuerClientTest {

    @Test
    void extractsIssuerSubjectAndClaimsFromIssuedVcResponse() {
        OpenDidIssuerApi api = mock(OpenDidIssuerApi.class);
        when(api.searchIssuedVcs("vcId", "vc-1", 1)).thenReturn(Map.of(
                "content", List.of(Map.of(
                        "vcId", "vc-1",
                        "status", "ACTIVE",
                        "issuer", Map.of("id", "did:omn:issuer"),
                        "credentialSubject", Map.of(
                                "id", "did:omn:holder",
                                "ns.videoCommitment", "root"
                        )
                ))
        ));

        OpenDidIssuerClient client = new OpenDidIssuerClient(
                api,
                new OpenDidProperties(true, "issuer", "plan", "ns"),
                new ObjectMapper());

        OpenDidIssuerClient.IssuedVc issuedVc = client.getIssuedVc("vc-1");

        assertThat(issuedVc.status()).isEqualTo("ACTIVE");
        assertThat(issuedVc.issuerDid()).isEqualTo("did:omn:issuer");
        assertThat(issuedVc.subjectDid()).isEqualTo("did:omn:holder");
        assertThat(issuedVc.claims()).containsEntry("ns.videoCommitment", "root");
    }
}
