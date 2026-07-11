package com.jinbon.infra.omnione.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class OacxTokenResponse {
    private String token;
    private String txId;
    private String oacxCode;
    private String resultCode;
}
