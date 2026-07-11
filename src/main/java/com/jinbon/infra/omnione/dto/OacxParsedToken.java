package com.jinbon.infra.omnione.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.Map;

@Getter
@NoArgsConstructor
public class OacxParsedToken {
    private Map<String, Object> data;
    private String oacxCode;
    private String resultCode;

    public String getCi() {
        return data != null ? (String) data.get("ci") : null;
    }

    public String getName() {
        return data != null ? (String) data.get("name") : null;
    }

    public String getBirth() {
        return data != null ? (String) data.get("birth") : null;
    }

    public String getUserDid() {
        return data != null ? (String) data.get("userDid") : null;
    }

    public String getProvider() {
        return data != null ? (String) data.get("provider") : null;
    }
}
