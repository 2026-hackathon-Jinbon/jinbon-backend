package com.jinbon.domain.auth.port;

/** Refresh Token 저장소 경계. 저장소 구현은 애플리케이션 외부에 둔다. */
public interface RefreshTokenStore {

    void save(Long memberId, String refreshToken);

    boolean rotate(Long memberId, String oldToken, String newToken);

    void delete(Long memberId);
}
