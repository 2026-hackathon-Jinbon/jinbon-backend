package com.jinbon.infra.opendid;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * Open DID Verifier Server REST API 클라이언트.
 *
 * VC 검증 흐름:
 * 1. VC 상태 조회 (active / revoked / expired)
 * 2. VC 서명 및 무결성 검증
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OpenDidVerifierClient {

    private final OpenDidVerifierApi api;

    /** VC 상태를 조회한다 (active / revoked / expired) */
    public Map<String, Object> getVcStatus(String vcId) {
        log.debug("Querying VC status - vcId={}", vcId);
        Map<String, Object> result = api.getVcStatus(vcId);
        log.info("VC status retrieved - vcId={}, status={}", vcId, result.get("status"));
        return result;
    }

    /** VC 서명 및 무결성을 검증한다 */
    public Map<String, Object> verifyVc(String vcId) {
        log.debug("Verifying VC integrity - vcId={}", vcId);
        Map<String, Object> body = Map.of(
                "id", UUID.randomUUID().toString(),
                "vcId", vcId
        );
        Map<String, Object> result = api.verifyVc(body);
        log.info("VC verification completed - vcId={}, result={}", vcId, result.get("result"));
        return result;
    }
}