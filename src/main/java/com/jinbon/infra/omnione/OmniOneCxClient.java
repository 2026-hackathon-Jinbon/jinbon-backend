package com.jinbon.infra.omnione;

import com.jinbon.infra.omnione.dto.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * OmniOne CX API 클라이언트 (필수과제 - 모바일 신분증 연동)
 *
 * 흐름: Token 생성 → WebToApp 요청 → 검증 요청 → Token 파싱
 */
@Slf4j
@Component
public class OmniOneCxClient {

    private final RestClient restClient;

    public OmniOneCxClient(@Value("${omnione.cx.server-url}") String serverUrl) {
        this.restClient = RestClient.builder()
                .baseUrl(serverUrl)
                .build();
    }

    /** 1. Token 요청 */
    public OacxTokenResponse requestToken() {
        return restClient.post()
                .uri("/oacx/api/v1.0/trans")
                .contentType(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(OacxTokenResponse.class);
    }

    /** 2. WebToApp 제출 요청 */
    public OacxAppResponse requestWebToApp(String provider, String token, String txId) {
        Map<String, Object> body = Map.of(
                "provider", provider + "_v1.5",
                "token", token,
                "txId", txId,
                "contentInfo", Map.of("signType", "ENT_MID")
        );

        return restClient.post()
                .uri("/oacx/api/v1.0/authen/app/request")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(OacxAppResponse.class);
    }

    /** 3. WebToApp 검증 요청 */
    public OacxResultResponse verifyApp(String provider, String token, String txId, String cxId) {
        Map<String, Object> body = Map.of(
                "provider", provider + "_v1.5",
                "token", token,
                "txId", txId,
                "cxId", cxId
        );

        return restClient.post()
                .uri("/oacx/api/v1.0/authen/app/result")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(OacxResultResponse.class);
    }

    /** 4. Token 파싱 (신원정보 추출) */
    public OacxParsedToken parseToken(String token) {
        Map<String, String> body = Map.of("token", token);

        return restClient.post()
                .uri("/oacx/api/v1.0/trans/token")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(OacxParsedToken.class);
    }
}
