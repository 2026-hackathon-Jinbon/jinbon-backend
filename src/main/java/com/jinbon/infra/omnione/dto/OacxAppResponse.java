package com.jinbon.infra.omnione.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.Map;

@Getter
@NoArgsConstructor
public class OacxAppResponse {
    private String token;
    private String cxId;
    private Map<String, Object> data;  // androidLink, iosLink, ssPayLink
    private String oacxStatus;
    private String oacxCode;
    private String resultCode;
    private String reqTxId;
    private String clientMessage;
    private String provider;
}
