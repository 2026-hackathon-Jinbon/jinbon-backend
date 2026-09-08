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

    /** Issuer 발급 원장에서 VC의 상태와 claim을 조회한다. */
    public IssuedVc getIssuedVc(String vcId) {
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
        return IssuedVc.from(issuedVc, objectMapper);
    }

    public IssuerDocument getIssuerDocument() {
        Map<String, Object> issuerInfo = api.getIssuerInfo();
        Object did = issuerInfo.get("did");
        Object didDocument = issuerInfo.get("didDocument");
        if (did == null || didDocument == null) {
            throw new IllegalStateException("Issuer response is missing DID document");
        }
        try {
            return new IssuerDocument(did.toString(), objectMapper.writeValueAsString(didDocument));
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize issuer DID document", e);
        }
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

    public record IssuedVc(String status, String issuerDid, String subjectDid,
                           Map<String, Object> claims) {
        @SuppressWarnings("unchecked")
        static IssuedVc from(Map<String, Object> issuedVc, ObjectMapper objectMapper) {
            String status = text(issuedVc, "status");
            String issuerDid = firstText(issuedVc, "issuerDid", "credentialIssuerDid");
            Object issuer = issuedVc.get("issuer");
            if (issuerDid == null && issuer instanceof Map<?, ?> issuerMap) {
                issuerDid = text((Map<String, Object>) issuerMap, "id", "did");
            } else if (issuerDid == null && issuer != null) {
                issuerDid = issuer.toString();
            }

            Map<String, Object> claims = extractClaims(issuedVc, objectMapper);
            String subjectDid = firstText(issuedVc, "subjectDid", "holderDid", "did");
            Object subject = issuedVc.get("credentialSubject");
            if (subject instanceof Map<?, ?> subjectMap) {
                Map<String, Object> values = (Map<String, Object>) subjectMap;
                if (subjectDid == null) {
                    subjectDid = firstText(values, "id", "did");
                }
                if (claims.isEmpty()) {
                    claims = values;
                }
            }
            return new IssuedVc(status, issuerDid, subjectDid, Map.copyOf(claims));
        }

        @SuppressWarnings("unchecked")
        private static Map<String, Object> extractClaims(Map<String, Object> issuedVc,
                                                         ObjectMapper objectMapper) {
            Object candidate = issuedVc.get("claims");
            if (candidate == null) {
                candidate = issuedVc.get("userInfo");
            }
            if (candidate instanceof String json) {
                try {
                    candidate = objectMapper.readValue(json, Map.class);
                } catch (JacksonException e) {
                    return Map.of();
                }
            }
            return candidate instanceof Map<?, ?> map
                    ? (Map<String, Object>) map : Map.of();
        }

        private static String firstText(Map<String, Object> values, String... keys) {
            for (String key : keys) {
                String value = text(values, key);
                if (value != null) {
                    return value;
                }
            }
            return null;
        }

        private static String text(Map<String, Object> values, String... keys) {
            for (String key : keys) {
                Object value = values.get(key);
                if (value != null && !value.toString().isBlank()) {
                    return value.toString();
                }
            }
            return null;
        }
    }

    public record IssuerDocument(String did, String json) {}

}
