package com.jinbon.domain.video.port;

import java.util.Map;

/** 영상 등록 보증서 발급 준비 경계. */
public interface CredentialIssuancePort {

    Preparation prepare(String holderDid, Map<String, Object> claims);

    void syncHolder(String holderDid, Map<String, Object> claims);

    record Preparation(String planId, String issuerDid, String offerId) {}
}
