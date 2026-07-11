package com.jinbon.infra.blockchain;

import com.jinbon.global.config.BlockchainProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.TransactionEncoder;
import org.web3j.crypto.WalletUtils;
import org.web3j.utils.Numeric;

import java.io.File;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;

/**
 * OmniOne Chain (BESU) 블록체인 클라이언트.
 *
 * JSON-RPC 기반으로 스마트 컨트랙트와 통신한다.
 * - 인증: API 토큰을 URL 쿼리 파라미터로 전달
 * - 서명: Keystore 파일로 로컬 서명 후 eth_sendRawTransaction 사용
 */
@Slf4j
@Component
public class OmniOneChainClient {

    private final RestClient restClient;
    private final BlockchainProperties properties;
    private final Credentials credentials;

    public OmniOneChainClient(BlockchainProperties properties, ResourceLoader resourceLoader) {
        this.properties = properties;

        // RPC URL에 API 토큰을 쿼리 파라미터로 추가
        String rpcUrlWithToken = properties.getRpcUrl() + "/?token=" + properties.getApiToken();
        this.restClient = RestClient.builder()
                .baseUrl(rpcUrlWithToken)
                .build();
        log.info("Blockchain RPC client initialized - contractAddress={}", properties.getContractAddress());

        // 트랜잭션 서명용 Keystore 크레덴셜 로드
        this.credentials = loadCredentials(properties, resourceLoader);
    }

    /**
     * Keystore 파일에서 크레덴셜을 로드한다.
     * classpath 리소스를 임시 파일로 복사 후 web3j로 로드한다.
     */
    private Credentials loadCredentials(BlockchainProperties properties, ResourceLoader resourceLoader) {
        try {
            InputStream is = resourceLoader.getResource(properties.getKeystorePath()).getInputStream();
            File tempFile = File.createTempFile("keystore", ".json");
            tempFile.deleteOnExit();
            Files.copy(is, tempFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            Credentials loaded = WalletUtils.loadCredentials(properties.getKeystorePassword(), tempFile);
            log.info("Keystore loaded successfully - walletAddress={}", properties.getWalletAddress());
            return loaded;
        } catch (Exception e) {
            log.warn("Failed to load keystore, transaction signing unavailable: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 스마트 컨트랙트 읽기 전용 호출 (eth_call).
     * 블록체인 상태를 변경하지 않으며, 가스비가 발생하지 않는다.
     */
    public String ethCall(String data) {
        log.debug("eth_call - to={}, dataLength={}", properties.getContractAddress(), data.length());

        Map<String, Object> body = Map.of(
                "jsonrpc", "2.0",
                "method", "eth_call",
                "params", List.of(
                        Map.of("to", properties.getContractAddress(), "data", data),
                        "latest"
                ),
                "id", 1
        );

        Map<String, Object> response = post(body);
        String result = response != null ? (String) response.get("result") : null;
        log.debug("eth_call result - resultLength={}", result != null ? result.length() : 0);
        return result;
    }

    /**
     * 스마트 컨트랙트 상태 변경 트랜잭션을 전송한다.
     *
     * 처리 흐름:
     * 1. eth_getTransactionCount로 nonce 조회
     * 2. eth_chainId로 체인 ID 조회
     * 3. RawTransaction 생성 (gasPrice=0, BESU 허가형 네트워크)
     * 4. Keystore 크레덴셜로 로컬 서명
     * 5. eth_sendRawTransaction으로 서명된 트랜잭션 전송
     *
     * @param data ABI 인코딩된 함수 호출 데이터
     * @return 트랜잭션 해시 (실패 시 null)
     */
    public String sendTransaction(String data) {
        if (credentials == null) {
            throw new RuntimeException("Keystore credentials not loaded");
        }

        // nonce 조회
        BigInteger nonce = getNonce();
        log.debug("Preparing transaction - nonce={}, to={}", nonce, properties.getContractAddress());

        // RawTransaction 생성
        RawTransaction rawTransaction = RawTransaction.createTransaction(
                nonce,
                BigInteger.valueOf(0),           // gasPrice (BESU 허가형 네트워크: 0)
                BigInteger.valueOf(4_500_000),    // gasLimit
                properties.getContractAddress(),
                BigInteger.ZERO,                  // value (ETH 전송 없음)
                data
        );

        // 로컬 서명
        long chainId = getChainId();
        byte[] signedMessage = TransactionEncoder.signMessage(rawTransaction, chainId, credentials);
        String hexValue = Numeric.toHexString(signedMessage);
        log.debug("Transaction signed - chainId={}, signedTxLength={}", chainId, hexValue.length());

        // 서명된 트랜잭션 전송
        Map<String, Object> body = Map.of(
                "jsonrpc", "2.0",
                "method", "eth_sendRawTransaction",
                "params", List.of(hexValue),
                "id", 1
        );

        Map<String, Object> response = post(body);
        if (response != null && response.containsKey("error")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> error = (Map<String, Object>) response.get("error");
            log.error("Transaction failed - error={}", error);
            return null;
        }

        String txHash = response != null ? (String) response.get("result") : null;
        log.info("Transaction sent successfully - txHash={}", txHash);
        return txHash;
    }

    /**
     * 트랜잭션 영수증을 조회한다.
     * 블록에 포함된 트랜잭션의 실행 결과(blockNumber, status, logs 등)를 반환한다.
     */
    public Map<String, Object> getTransactionReceipt(String txHash) {
        log.debug("Fetching transaction receipt - txHash={}", txHash);

        Map<String, Object> body = Map.of(
                "jsonrpc", "2.0",
                "method", "eth_getTransactionReceipt",
                "params", List.of(txHash),
                "id", 1
        );

        Map<String, Object> response = post(body);
        @SuppressWarnings("unchecked")
        Map<String, Object> receipt = response != null ? (Map<String, Object>) response.get("result") : null;

        if (receipt != null) {
            log.info("Receipt retrieved - txHash={}, blockNumber={}, status={}",
                    txHash, receipt.get("blockNumber"), receipt.get("status"));
        } else {
            log.warn("Receipt not found (not yet mined) - txHash={}", txHash);
        }
        return receipt;
    }

    /** 현재 계정의 트랜잭션 카운트(nonce)를 조회한다 */
    private BigInteger getNonce() {
        Map<String, Object> body = Map.of(
                "jsonrpc", "2.0",
                "method", "eth_getTransactionCount",
                "params", List.of(properties.getWalletAddress(), "latest"),
                "id", 1
        );

        Map<String, Object> response = post(body);
        String result = response != null ? (String) response.get("result") : "0x0";
        return Numeric.decodeQuantity(result);
    }

    /** 현재 연결된 체인의 ID를 조회한다 */
    private long getChainId() {
        Map<String, Object> body = Map.of(
                "jsonrpc", "2.0",
                "method", "eth_chainId",
                "params", List.of(),
                "id", 1
        );

        Map<String, Object> response = post(body);
        String result = response != null ? (String) response.get("result") : "0x1";
        return Numeric.decodeQuantity(result).longValue();
    }

    /** JSON-RPC POST 요청을 전송한다 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> post(Map<String, Object> body) {
        log.debug("JSON-RPC call - method={}", body.get("method"));

        Map<String, Object> response = restClient.post()
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Map.class);

        log.debug("JSON-RPC response - method={}, hasResult={}, hasError={}",
                body.get("method"),
                response != null && response.containsKey("result"),
                response != null && response.containsKey("error"));
        return response;
    }
}
