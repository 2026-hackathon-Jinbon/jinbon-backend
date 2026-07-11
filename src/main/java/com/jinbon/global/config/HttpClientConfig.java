package com.jinbon.global.config;

import com.jinbon.infra.omnione.OmniOneCxApi;
import com.jinbon.infra.opendid.OpenDidIssuerApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

/**
 * @HttpExchange 기반 HTTP 클라이언트 프록시 설정.
 *
 * RestClient + HttpServiceProxyFactory를 사용하여
 * 선언적 인터페이스(@HttpExchange)를 실제 HTTP 클라이언트로 변환한다.
 */
@Configuration
public class HttpClientConfig {

    /** OmniOne CX 모바일 신분증 API 클라이언트 */
    @Bean
    OmniOneCxApi omniOneCxApi(@Value("${omnione.cx.server-url}") String serverUrl) {
        RestClient restClient = RestClient.builder().baseUrl(serverUrl).build();
        return createProxy(restClient, OmniOneCxApi.class);
    }

    /** Open DID Issuer Server API 클라이언트 */
    @Bean
    OpenDidIssuerApi openDidIssuerApi(OpenDidProperties properties) {
        RestClient restClient = RestClient.builder().baseUrl(properties.getIssuerServerUrl()).build();
        return createProxy(restClient, OpenDidIssuerApi.class);
    }

    /** RestClient 기반 @HttpExchange 프록시를 생성한다 */
    private <T> T createProxy(RestClient restClient, Class<T> clientType) {
        RestClientAdapter adapter = RestClientAdapter.create(restClient);
        HttpServiceProxyFactory factory = HttpServiceProxyFactory.builderFor(adapter).build();
        return factory.createClient(clientType);
    }
}
