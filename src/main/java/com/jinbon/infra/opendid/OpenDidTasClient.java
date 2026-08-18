package com.jinbon.infra.opendid;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class OpenDidTasClient {

    private final OpenDidTasAdminApi api;

    public String getHolderPii(String holderDid) {
        Map<String, Object> response = api.searchUsers("did", holderDid, 10);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> users = (List<Map<String, Object>>) response.get("content");
        if (users == null) {
            throw new IllegalStateException("TAS Holder does not exist: " + holderDid);
        }
        return users.stream()
                .filter(user -> holderDid.equals(String.valueOf(user.get("did"))))
                .map(user -> user.get("pii"))
                .filter(pii -> pii != null && !pii.toString().isBlank())
                .map(Object::toString)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("TAS Holder PII does not exist: " + holderDid));
    }
}
