package com.jinbon.infra.opendid;

import com.jinbon.global.config.OpenDidProperties;
import com.jinbon.global.error.BusinessException;
import com.jinbon.global.error.ErrorCode;
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

    /**
     * 영상 진본 증명 VC를 발급한다.
     *
     * @param holderDid VC를 받을 Holder(공인)의 DID
     * @return 발급된 VC ID (disabled 시 null)
     */
    public String issueVideoAuthenticityVc(String holderDid) {
        if (!openDidProperties.isEnabled()) {
            log.info("Open DID is disabled, skipping VC issuance - holderDid={}", holderDid);
            return null;
        }

        log.info("Starting VC issuance flow - holderDid={}", holderDid);

        try {
            // Step 1: 발급 세션 생성
            Map<String, Object> offerResult = issuerClient.requestOffer();
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = (Map<String, Object>) offerResult.get("issueOfferPayload");
            String offerId = payload != null ? (String) payload.get("offerId") : null;
            if (offerId == null) {
                log.error("Offer creation failed - no offerId in response");
                throw new BusinessException(ErrorCode.VC_ISSUANCE_FAILED);
            }

            // Step 2: 발급 요청 유효성 검증
            Map<String, Object> proposeResult = issuerClient.inspectProposeIssue(offerId);
            String txId = (String) proposeResult.get("txId");
            if (txId == null) {
                log.error("Propose inspection failed - no txId in response, offerId={}", offerId);
                throw new BusinessException(ErrorCode.VC_ISSUANCE_FAILED);
            }

            // Step 3: 발급 프로필 생성
            issuerClient.generateIssueProfile(txId, holderDid);

            // Step 4: Wallet 앱이 Issuer에 issue-vc 요청을 완료한 후, 결과 폴링
            Map<String, Object> vcResult = issuerClient.getIssueVcResult(txId);
            String vcId = (String) vcResult.get("vcId");

            if (vcId == null) {
                log.error("VC issuance failed - wallet app did not complete issue-vc, txId={}", txId);
                throw new BusinessException(ErrorCode.VC_ISSUANCE_FAILED);
            }

            // Step 5: 발급 완료 처리
            issuerClient.completeVc(txId, vcId);

            log.info("VC issuance flow completed - txId={}, vcId={}", txId, vcId);
            return vcId;

        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("VC issuance flow failed - holderDid={}", holderDid, e);
            throw new BusinessException(ErrorCode.VC_ISSUANCE_FAILED);
        }
    }
}
