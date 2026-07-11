package com.jinbon.infra.opendid;

import com.jinbon.global.error.BusinessException;
import com.jinbon.global.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Open DID VC 발급 플로우를 오케스트레이션하는 서비스.
 *
 * Issuer-Initiated 방식:
 *   request-offer → inspect-propose → generate-profile → (issue-vc → complete-vc)
 *
 * MVP 단계에서는 Step 4~5(Wallet 앱 E2E 암호화)가 불가하므로,
 * result 폴링으로 vcId를 조회하거나 txId를 대신 반환한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VcIssuanceService {

    private final OpenDidIssuerClient issuerClient;

    /**
     * 영상 진본 증명 VC를 발급한다.
     *
     * @param holderDid VC를 받을 Holder(공인)의 DID
     * @return 발급된 VC ID (MVP에서는 txId가 반환될 수 있음)
     */
    public String issueVideoAuthenticityVc(String holderDid) {
        log.info("Starting VC issuance flow - holderDid={}", holderDid);

        try {
            // Step 1: 발급 세션 생성
            Map<String, Object> offerResult = issuerClient.requestOffer();
            String offerId = (String) offerResult.get("offerId");
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

            // Step 4~5: Wallet 앱 연동 필요 (MVP에서는 result 폴링으로 대체)
            Map<String, Object> vcResult = issuerClient.getIssueVcResult(txId);
            String vcId = (String) vcResult.get("vcId");

            if (vcId == null) {
                log.warn("VC not yet issued (pending wallet interaction) - txId={}, returning txId as fallback", txId);
                return txId;
            }

            log.info("VC issuance flow completed - vcId={}, holderDid={}", vcId, holderDid);
            return vcId;

        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("VC issuance flow failed - holderDid={}", holderDid, e);
            throw new BusinessException(ErrorCode.VC_ISSUANCE_FAILED);
        }
    }
}
