package com.jinbon.infra.opendid;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Open DID VC 검증 서비스.
 *
 * 검증 항목:
 * 1. VC 상태 확인 — active / revoked / expired
 * 2. VC 서명 무결성 검증 — 발급 기관의 서명이 유효한지 확인
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VcVerificationService {

    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String RESULT_VALID = "VALID";

    private final OpenDidVerifierClient verifierClient;
    private final com.jinbon.global.config.OpenDidProperties openDidProperties;

    /**
     * VC를 검증한다.
     * 상태 확인(active) + 서명 무결성 검증을 수행한다.
     *
     * @param vcId 검증할 VC ID
     * @return 검증 통과 여부
     */
    public boolean verify(String vcId) {
        if (!openDidProperties.isEnabled()) {
            log.info("Open DID is disabled, skipping VC verification - vcId={}", vcId);
            return true;
        }

        log.info("Starting VC verification - vcId={}", vcId);

        try {
            // 1. VC 상태 확인 (active / revoked / expired)
            Map<String, Object> statusResult = verifierClient.getVcStatus(vcId);
            String status = (String) statusResult.get("status");

            if (!STATUS_ACTIVE.equalsIgnoreCase(status)) {
                log.warn("VC is not active - vcId={}, status={}", vcId, status);
                return false;
            }

            // 2. VC 서명 무결성 검증
            Map<String, Object> verifyResult = verifierClient.verifyVc(vcId);
            String result = (String) verifyResult.get("result");

            if (!RESULT_VALID.equalsIgnoreCase(result)) {
                log.warn("VC integrity verification failed - vcId={}, result={}", vcId, result);
                return false;
            }

            log.info("VC verification passed - vcId={}", vcId);
            return true;

        } catch (Exception e) {
            log.warn("VC verification failed - vcId={}, reason={}", vcId, e.getMessage());
            return false;
        }
    }
}