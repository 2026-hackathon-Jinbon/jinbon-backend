package com.jinbon.infra.opendid;

import com.jinbon.global.config.OpenDidProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Open DID VC 발급 플로우를 오케스트레이션하는 서비스.
 *
 * opendid.enabled=true 일 때 Issuer-Initiated 방식으로 VC를 발급한다:
 *   request-offer → inspect-propose → generate-profile → issue-vc → complete-vc
 *
 * opendid.enabled=false 일 때 VC 발급을 건너뛴다 (Open DID Orchestrator 미구동 환경용).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VcIssuanceService {

    private final OpenDidIssuerClient issuerClient;
    private final OpenDidProperties openDidProperties;

    public VcIssuancePreparation prepareVideoVc(String holderDid, Map<String, Object> claims) {
        if (!openDidProperties.isEnabled()) {
            return null;
        }
        issuerClient.prepareHolder(holderDid, claims);
        OpenDidIssuerClient.IssueOffer offer = issuerClient.createIssueOffer();
        return new VcIssuancePreparation(
                openDidProperties.getVcPlanId(), offer.issuerDid(), offer.offerId());
    }

    public record VcIssuancePreparation(String vcPlanId, String issuerDid, String offerId) {}

}
