package com.jinbon.infra.opendid;

import com.jinbon.global.config.OpenDidProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.List;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Open DID Issuer Server REST API 클라이언트.
 *
 * Wallet VC 발급을 위한 Issuer 연동과 레거시 Issuer-Initiated API를 제공한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OpenDidIssuerClient {

    private final OpenDidIssuerApi api;
    private final OpenDidProperties properties;
    private final ObjectMapper objectMapper;

    /** Step 1: 발급 세션을 생성하고 offerId를 획득한다 */
    public Map<String, Object> requestOffer() {
        log.debug("Requesting VC offer - vcPlanId={}", properties.getVcPlanId());
        Map<String, Object> result = api.requestOffer(Map.of("vcPlanId", properties.getVcPlanId()));
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) result.get("issueOfferPayload");
        log.info("VC offer created - offerId={}", payload != null ? payload.get("offerId") : null);
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

    public void prepareHolder(String holderDid, Map<String, Object> claims) {
        // Issuer 2.0.0은 vcPlanId 검색 필터가 일치하는 Plan도 빈 목록으로 반환할 수 있어
        // 전체 목록에서 정확한 Plan ID를 직접 찾는다.
        Map<String, Object> profiles = api.listIssueProfiles(100);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) profiles.get("content");
        Map<String, Object> profile = content == null ? null : content.stream()
                .filter(item -> properties.getVcPlanId().equals(String.valueOf(item.get("vcPlanId"))))
                .findFirst()
                .orElse(null);
        if (profile == null || profile.get("vcSchemaId") == null) {
            throw new IllegalStateException("No VC schema configured for plan: " + properties.getVcPlanId());
        }
        String vcSchemaId = profile.get("vcSchemaId").toString();
        try {
            String userInfo = objectMapper.writeValueAsString(claims);
            String pii = createPii(holderDid);
            Map<String, Object> holders = api.searchHolders("did", holderDid, 1);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> registered = (List<Map<String, Object>>) holders.get("content");

            if (registered == null || registered.isEmpty()) {
                api.registerHolder(Map.of(
                        "did", holderDid,
                        "pii", pii,
                        "vcSchemaId", vcSchemaId,
                        "userInfo", userInfo
                ));
                log.info("Issuer holder registered - holderDid={}, vcSchemaId={}", holderDid, vcSchemaId);
            } else {
                Map<String, Object> holder = registered.getFirst();
                Object id = holder.get("id");
                Object numericSchemaId = holder.get("vcSchemaId");
                if (id == null || numericSchemaId == null) {
                    throw new IllegalStateException("Issuer holder response is missing id or vcSchemaId");
                }
                api.updateHolder(Map.of(
                        "id", id,
                        "did", holderDid,
                        "pii", pii,
                        "vcSchemaId", numericSchemaId,
                        "userInfo", userInfo
                ));
                log.info("Issuer holder claims updated - holderDid={}, vcSchemaId={}", holderDid, vcSchemaId);
            }
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize VC claims", e);
        }
    }

    public IssueOffer createIssueOffer() {
        Map<String, Object> result = requestOffer();
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) result.get("issueOfferPayload");
        Object offerId = payload != null ? payload.get("offerId") : null;
        Object issuer = payload != null ? payload.get("issuer") : null;
        if (offerId == null || issuer == null) {
            throw new IllegalStateException("Issuer did not return an offerId or issuer");
        }
        return new IssueOffer(offerId.toString(), issuer.toString());
    }

    public record IssueOffer(String offerId, String issuerDid) {}

    private String createPii(String holderDid) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(holderDid.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    public String getIssuerDid() {
        Object did = api.getIssuerInfo().get("did");
        return did != null ? did.toString() : properties.getIssuerDid();
    }
}
