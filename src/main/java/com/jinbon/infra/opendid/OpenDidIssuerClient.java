package com.jinbon.infra.opendid;

import com.jinbon.global.config.OpenDidProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * Open DID Issuer Server REST API 클라이언트.
 *
 * Issuer-Initiated VC 발급 흐름:
 * 1. request-offer     → offerId 획득
 * 2. inspect-propose   → txId 획득
 * 3. generate-profile  → 발급 프로필 생성
 * 4. issue-vc          → VC 발급
 * 5. complete-vc       → 발급 완료 처리
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OpenDidIssuerClient {

    private final OpenDidIssuerApi api;
    private final OpenDidProperties properties;

    /** Step 1: 발급 세션을 생성하고 offerId를 획득한다 */
    public Map<String, Object> requestOffer() {
        log.debug("Requesting VC offer - vcPlanId={}", properties.getVcPlanId());
        Map<String, Object> result = api.requestOffer(Map.of("vcPlanId", properties.getVcPlanId()));
        log.info("VC offer created - offerId={}", result.get("offerId"));
        return result;
    }

    /** Step 2: 발급 요청의 유효성을 검증하고 txId를 획득한다 */
    public Map<String, Object> inspectProposeIssue(String offerId) {
        log.debug("Inspecting propose issue - offerId={}", offerId);
        Map<String, Object> body = Map.of(
                "id", UUID.randomUUID().toString(),
                "vcPlanId", properties.getVcPlanId(),
                "issuer", properties.getIssuerDid(),
                "offerId", offerId
        );
        Map<String, Object> result = api.inspectProposeIssue(body);
        log.info("Propose inspected - txId={}", result.get("txId"));
        return result;
    }

    /** Step 3: Holder DID에 대한 발급 프로필을 생성한다 */
    public void generateIssueProfile(String txId, String holderDid) {
        log.debug("Generating issue profile - txId={}, holderDid={}", txId, holderDid);
        Map<String, Object> body = Map.of(
                "id", UUID.randomUUID().toString(),
                "txId", txId,
                "holder", Map.of("did", holderDid)
        );
        api.generateIssueProfile(body);
        log.info("Issue profile generated - txId={}", txId);
    }

    /** Step 4: VC 발급을 요청한다 (E2E 암호화 필요) */
    public Map<String, Object> issueVc(String txId, Map<String, Object> accE2e, String encReqVc) {
        log.debug("Issuing VC - txId={}", txId);
        Map<String, Object> body = Map.of(
                "id", UUID.randomUUID().toString(),
                "txId", txId,
                "accE2e", accE2e,
                "encReqVc", encReqVc
        );
        Map<String, Object> result = api.issueVc(body);
        log.info("VC issued - txId={}", txId);
        return result;
    }

    /** Step 5: VC 발급을 완료 처리한다 */
    public Map<String, Object> completeVc(String txId, String vcId) {
        log.debug("Completing VC issuance - txId={}, vcId={}", txId, vcId);
        Map<String, Object> result = api.completeVc(Map.of(
                "id", UUID.randomUUID().toString(),
                "txId", txId,
                "vcId", vcId
        ));
        log.info("VC issuance completed - txId={}, vcId={}", txId, vcId);
        return result;
    }

    /** 발급 결과를 폴링 조회한다 (Wallet 앱 연동 전 MVP용) */
    public Map<String, Object> getIssueVcResult(String txId) {
        log.debug("Polling VC issuance result - txId={}", txId);
        Map<String, Object> result = api.getIssueVcResult(txId);
        log.info("VC issuance result - txId={}, vcId={}", txId, result.get("vcId"));
        return result;
    }
}
