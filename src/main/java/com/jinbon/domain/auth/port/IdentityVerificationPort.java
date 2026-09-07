package com.jinbon.domain.auth.port;

import java.util.Map;

/** 모바일 신분증 공급자와 무관한 본인확인 경계. */
public interface IdentityVerificationPort {

    VerificationSession createSession();

    AppRequest requestApp(String provider, String token, String txId);

    VerifiedIdentity verify(String provider, String token, String txId, String cxId);

    record VerificationSession(String token, String txId, String oacxCode, String resultCode) {}

    record AppRequest(
            String token,
            String cxId,
            Map<String, Object> data,
            String oacxStatus,
            String oacxCode,
            String resultCode,
            String reqTxId,
            String clientMessage,
            String provider
    ) {}

    record VerifiedIdentity(String ci, String name, String birth) {}
}
