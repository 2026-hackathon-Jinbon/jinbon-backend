package com.jinbon.infra.omnione.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.Map;

@Getter
@NoArgsConstructor
public class OacxResultResponse {
    private String token;
    private String txId;
    private String cxId;
    private Map<String, Object> data;
    private String oacxStatus;
    private String oacxCode;
    private String resultCode;
    private String reqTxId;
    private String clientMessage;
    private String provider;
}
