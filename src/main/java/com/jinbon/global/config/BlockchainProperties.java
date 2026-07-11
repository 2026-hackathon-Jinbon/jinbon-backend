package com.jinbon.global.config;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@RequiredArgsConstructor
@ConfigurationProperties(prefix = "blockchain")
public class BlockchainProperties {

    private final String rpcUrl;
    private final String contractAddress;
    private final String walletAddress;
    private final String keystorePath;
    private final String keystorePassword;
    private final String apiToken;
}
