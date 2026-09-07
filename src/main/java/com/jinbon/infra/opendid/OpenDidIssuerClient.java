package com.jinbon.infra.opendid;

import com.jinbon.global.config.OpenDidProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.List;
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

    /** Issuer 발급 원장에서 VC의 현재 상태를 조회한다. */
    public String getIssuedVcStatus(String vcId) {
        log.debug("Querying issued VC status - vcId={}", vcId);
        Map<String, Object> result = api.searchIssuedVcs("vcId", vcId, 1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
        if (content == null || content.isEmpty()) {
            return null;
        }
        Map<String, Object> issuedVc = content.getFirst();
        if (!vcId.equals(String.valueOf(issuedVc.get("vcId")))) {
            return null;
        }
        Object status = issuedVc.get("status");
        return status != null ? status.toString() : null;
    }

    public void prepareHolder(String holderDid, String pii, Map<String, Object> claims) {
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

}
