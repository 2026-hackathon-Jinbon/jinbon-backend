package com.jinbon.global.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OpenDidPropertiesTest {

    @Test
    void qualifiesClaimWithConfiguredNamespace() {
        OpenDidProperties properties = properties("ns-jinbon-video-01");

        assertThat(properties.claimKey("videoHash"))
                .isEqualTo("ns-jinbon-video-01.videoHash");
    }

    @Test
    void acceptsNamespaceWithTrailingDot() {
        OpenDidProperties properties = properties("ns-jinbon-video-01.");

        assertThat(properties.claimKey("videoTitle"))
                .isEqualTo("ns-jinbon-video-01.videoTitle");
    }

    private OpenDidProperties properties(String namespace) {
        return new OpenDidProperties(
                true,
                "http://localhost:8091",
                "http://localhost:8092",
                "vcplan-jinbon-01",
                namespace,
                "did:omn:issuer"
        );
    }
}
