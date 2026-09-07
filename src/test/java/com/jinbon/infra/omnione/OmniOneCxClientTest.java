package com.jinbon.infra.omnione;

import com.jinbon.domain.auth.port.IdentityVerificationPort.AppRequest;
import com.jinbon.domain.auth.port.IdentityVerificationPort.VerificationSession;
import com.jinbon.domain.auth.port.IdentityVerificationPort.VerifiedIdentity;
import com.jinbon.global.error.BusinessException;
import com.jinbon.infra.omnione.dto.OacxAppResponse;
import com.jinbon.infra.omnione.dto.OacxParsedToken;
import com.jinbon.infra.omnione.dto.OacxResultResponse;
import com.jinbon.infra.omnione.dto.OacxTokenResponse;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OmniOneCxClientTest {

    private final OmniOneCxApi api = mock(OmniOneCxApi.class);
    private final OmniOneCxClient client = new OmniOneCxClient(api);

    @Test
    void mapsSessionResponseToPortModel() {
        OacxTokenResponse response = mock(OacxTokenResponse.class);
        when(response.getToken()).thenReturn("token");
        when(response.getTxId()).thenReturn("tx-1");
        when(response.getOacxCode()).thenReturn("code");
        when(response.getResultCode()).thenReturn("200");
        when(api.requestToken()).thenReturn(response);

        VerificationSession result = client.createSession();

        assertThat(result).isEqualTo(new VerificationSession("token", "tx-1", "code", "200"));
    }

    @Test
    void mapsAppRequestWithoutLeakingInfrastructureDto() {
        OacxAppResponse response = mock(OacxAppResponse.class);
        Map<String, Object> links = Map.of("iosLink", "app://open");
        when(response.getToken()).thenReturn("token");
        when(response.getCxId()).thenReturn("cx-1");
        when(response.getData()).thenReturn(links);
        when(api.requestWebToApp(anyMap())).thenReturn(response);

        AppRequest result = client.requestApp("comdl", "token", "tx-1");

        assertThat(result.token()).isEqualTo("token");
        assertThat(result.cxId()).isEqualTo("cx-1");
        assertThat(result.data()).isEqualTo(links);
    }

    @Test
    void returnsVerifiedIdentityOnlyAfterCompletedVerification() {
        OacxResultResponse verification = mock(OacxResultResponse.class);
        when(verification.getResultCode()).thenReturn("200");
        when(verification.getToken()).thenReturn("verified-token");
        when(api.verifyApp(anyMap())).thenReturn(verification);
        OacxParsedToken parsed = mock(OacxParsedToken.class);
        when(parsed.getCi()).thenReturn("ci");
        when(parsed.getName()).thenReturn("홍길동");
        when(parsed.getBirth()).thenReturn("19900101");
        when(api.parseToken(Map.of("token", "verified-token"))).thenReturn(parsed);

        VerifiedIdentity result = client.verify("comdl", "token", "tx-1", "cx-1");

        assertThat(result).isEqualTo(new VerifiedIdentity("ci", "홍길동", "19900101"));
        verify(api).parseToken(Map.of("token", "verified-token"));
    }

    @Test
    void mapsPendingVerificationToBusinessError() {
        OacxResultResponse response = mock(OacxResultResponse.class);
        when(response.getResultCode()).thenReturn("408");
        when(api.verifyApp(anyMap())).thenReturn(response);

        assertThatThrownBy(() -> client.verify("comdl", "token", "tx-1", "cx-1"))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo("A007");
    }

    @Test
    void mapsRejectedVerificationToBusinessError() {
        OacxResultResponse response = mock(OacxResultResponse.class);
        when(response.getResultCode()).thenReturn("500");
        when(api.verifyApp(anyMap())).thenReturn(response);

        assertThatThrownBy(() -> client.verify("comdl", "token", "tx-1", "cx-1"))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo("A001");
    }
}
