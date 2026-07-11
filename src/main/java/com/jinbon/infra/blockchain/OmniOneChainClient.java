package com.jinbon.infra.blockchain;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * OmniOne Chain 클라이언트 (선택과제 2 - BESU 블록체인)
 *
 * JSON-RPC를 통해 스마트 컨트랙트와 통신
 */
@Slf4j
@Component
public class OmniOneChainClient {

    private final RestClient restClient;
    private final String contractAddress;

    public OmniOneChainClient(
            @Value("${blockchain.rpc-url}") String rpcUrl,
            @Value("${blockchain.contract-address}") String contractAddress) {
        this.restClient = RestClient.builder().baseUrl(rpcUrl).build();
        this.contractAddress = contractAddress;
    }

    /**
     * 스마트 컨트랙트 함수 호출 (읽기 전용)
     */
    public String ethCall(String data) {
        Map<String, Object> body = Map.of(
                "jsonrpc", "2.0",
                "method", "eth_call",
                "params", List.of(
                        Map.of("to", contractAddress, "data", data),
                        "latest"
                ),
                "id", 1
        );

        Map<String, Object> response = restClient.post()
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Map.class);

        return response != null ? (String) response.get("result") : null;
    }

    /**
     * 트랜잭션 전송 (쓰기)
     */
    public String sendTransaction(String from, String data) {
        Map<String, Object> body = Map.of(
                "jsonrpc", "2.0",
                "method", "eth_sendTransaction",
                "params", List.of(
                        Map.of(
                                "from", from,
                                "to", contractAddress,
                                "data", data
                        )
                ),
                "id", 1
        );

        Map<String, Object> response = restClient.post()
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Map.class);

        return response != null ? (String) response.get("result") : null;
    }

    /**
     * 트랜잭션 영수증 조회
     */
    public Map<String, Object> getTransactionReceipt(String txHash) {
        Map<String, Object> body = Map.of(
                "jsonrpc", "2.0",
                "method", "eth_getTransactionReceipt",
                "params", List.of(txHash),
                "id", 1
        );

        Map<String, Object> response = restClient.post()
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Map.class);

        return response != null ? (Map<String, Object>) response.get("result") : null;
    }
}
