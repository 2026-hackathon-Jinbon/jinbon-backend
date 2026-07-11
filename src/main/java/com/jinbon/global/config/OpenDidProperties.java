package com.jinbon.global.config;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@RequiredArgsConstructor
@ConfigurationProperties(prefix = "opendid")
public class OpenDidProperties {

    private final String issuerServerUrl;
    private final String vcPlanId;
    private final String issuerDid;
}
