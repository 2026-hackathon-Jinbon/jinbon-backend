package com.jinbon.domain.video.port;

import java.util.Map;

/** 발급된 영상 등록 보증서의 상태 검증 경계. */
public interface CredentialVerificationPort {

    VerificationResult verify(String credentialId);

    VerificationResult verify(String credentialId, String credentialJson);

    record VerificationResult(Status status, String issuerDid, String subjectDid,
                               Map<String, Object> claims) {
        public static VerificationResult unavailable() {
            return new VerificationResult(Status.UNAVAILABLE, null, null, Map.of());
        }

        public static VerificationResult disabled() {
            return new VerificationResult(Status.DISABLED, null, null, Map.of());
        }

        public static VerificationResult invalid() {
            return new VerificationResult(Status.INVALID, null, null, Map.of());
        }
    }

    enum Status {
        VERIFIED,
        INVALID,
        UNAVAILABLE,
        DISABLED
    }
}
