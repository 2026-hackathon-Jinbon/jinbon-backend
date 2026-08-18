package com.jinbon.infra.opendid;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Open DID Issuer 발급 원장에서 VC 상태를 확인하는 서비스.
 * VC의 암호학적 검증은 VC 원문을 제출하는 VP 검증 흐름에서 수행한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VcVerificationService {
    public enum VerificationStatus {
        VERIFIED, INVALID, UNAVAILABLE, DISABLED
    }

    private static final String STATUS_ACTIVE = "ACTIVE";
    private final OpenDidIssuerClient issuerClient;
    private final com.jinbon.global.config.OpenDidProperties openDidProperties;

    /**
     * Issuer 발급 원장에 VC가 존재하고 활성 상태인지 확인한다.
     *
     * @param vcId 검증할 VC ID
     * @return 검증 통과 여부
     */
    public boolean verify(String vcId) {
        return verifyStatus(vcId) == VerificationStatus.VERIFIED;
    }

    public VerificationStatus verifyStatus(String vcId) {
        if (!openDidProperties.isEnabled()) {
            log.info("Open DID is disabled, VC cannot be verified - vcId={}", vcId);
            return VerificationStatus.DISABLED;
        }

        log.info("Starting VC verification - vcId={}", vcId);

        try {
            String status = issuerClient.getIssuedVcStatus(vcId);

            if (!STATUS_ACTIVE.equalsIgnoreCase(status)) {
                log.warn("VC is not active - vcId={}, status={}", vcId, status);
                return VerificationStatus.INVALID;
            }

            log.info("Issued VC is active - vcId={}", vcId);
            return VerificationStatus.VERIFIED;

        } catch (Exception e) {
            log.warn("VC verification failed - vcId={}, reason={}", vcId, e.getMessage());
            return VerificationStatus.UNAVAILABLE;
        }
    }
}
