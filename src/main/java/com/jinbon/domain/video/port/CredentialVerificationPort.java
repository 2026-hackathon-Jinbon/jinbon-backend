package com.jinbon.domain.video.port;

/** 발급된 영상 등록 보증서의 상태 검증 경계. */
public interface CredentialVerificationPort {

    Status verify(String credentialId);

    enum Status {
        VERIFIED,
        INVALID,
        UNAVAILABLE,
        DISABLED
    }
}
