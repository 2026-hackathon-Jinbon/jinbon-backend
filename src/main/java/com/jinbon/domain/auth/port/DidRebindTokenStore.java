package com.jinbon.domain.auth.port;

/** DID 재연결용 1회성 토큰 저장소 경계. */
public interface DidRebindTokenStore {

    void save(Long memberId, String token);

    boolean consume(Long memberId, String token);
}
