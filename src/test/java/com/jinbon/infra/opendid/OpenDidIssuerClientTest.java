package com.jinbon.infra.opendid;

import com.jinbon.global.config.OpenDidProperties;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

    @Test
    void prepareHolderUpdatesExistingRowWhenDidWasRebound() {
        OpenDidIssuerApi api = mock(OpenDidIssuerApi.class);
        stubIssueProfile(api);
        // 같은 PII의 홀더가 옛 DID로 이미 등록돼 있다 (앱 재설치 후 DID rebind).
        when(api.listHolders(anyInt())).thenReturn(Map.of(
                "content", List.of(Map.of(
                        "id", 3,
                        "pii", "pii-1",
                        "did", "did:omn:old",
                        "vcSchemaId", 4,
                        "vcSchemaName", "schema-1"
                ))
        ));

        newClient(api).prepareHolder("did:omn:new", "pii-1", Map.of("ns.claim", "v"));

        // 새 행을 만들지 않고 기존 id를 새 DID로 갱신해야 한다.
        verify(api, never()).registerHolder(any());
        verify(api).updateHolder(argThat(body ->
                body.get("id").equals(3)
                        && body.get("did").equals("did:omn:new")
                        && body.get("pii").equals("pii-1")
                        && body.get("vcSchemaId").equals(4)));
    }

    @Test
    void prepareHolderRegistersWhenPiiIsUnknown() {
        OpenDidIssuerApi api = mock(OpenDidIssuerApi.class);
        stubIssueProfile(api);
        // 다른 사람의 홀더만 등록돼 있다.
        when(api.listHolders(anyInt())).thenReturn(Map.of(
                "content", List.of(Map.of(
                        "id", 2,
                        "pii", "pii-other",
                        "did", "did:omn:other",
                        "vcSchemaId", 4,
                        "vcSchemaName", "schema-1"
                ))
        ));

        newClient(api).prepareHolder("did:omn:new", "pii-1", Map.of("ns.claim", "v"));

        verify(api, never()).updateHolder(any());
        verify(api).registerHolder(argThat(body ->
                body.get("did").equals("did:omn:new")
                        && body.get("pii").equals("pii-1")
                        && body.get("vcSchemaId").equals("schema-1")));
    }

    @Test
    void prepareHolderIgnoresSamePiiUnderDifferentSchema() {
        OpenDidIssuerApi api = mock(OpenDidIssuerApi.class);
        stubIssueProfile(api);
        // PII는 같지만 다른 스키마의 홀더 — 갱신 대상이 아니다.
        when(api.listHolders(anyInt())).thenReturn(Map.of(
                "content", List.of(Map.of(
                        "id", 5,
                        "pii", "pii-1",
                        "did", "did:omn:old",
                        "vcSchemaId", 9,
                        "vcSchemaName", "other-schema"
                ))
        ));

        newClient(api).prepareHolder("did:omn:new", "pii-1", Map.of("ns.claim", "v"));

        verify(api, never()).updateHolder(any());
        verify(api).registerHolder(any());
    }

    private static void stubIssueProfile(OpenDidIssuerApi api) {
        when(api.listIssueProfiles(anyInt())).thenReturn(Map.of(
                "content", List.of(Map.of("vcPlanId", "plan", "vcSchemaId", "schema-1"))
        ));
    }

    private static OpenDidIssuerClient newClient(OpenDidIssuerApi api) {
        return new OpenDidIssuerClient(
                api,
                new OpenDidProperties(true, "issuer", "plan", "ns"),
                new ObjectMapper());
    }
}
