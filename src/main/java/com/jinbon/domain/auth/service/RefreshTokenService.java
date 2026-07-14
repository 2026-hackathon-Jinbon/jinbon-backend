package com.jinbon.domain.auth.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * Refresh Token 관리 서비스.
 *
 * Redis에 memberId별로 단일 Refresh Token을 저장한다.
 * Refresh Token Rotation: 갱신 시 새 토큰을 저장하면 기존 토큰은 자동 무효화된다.
 */
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private static final String KEY_PREFIX = "refresh:";

    private final RedisTemplate<String, String> redisTemplate;
    private final JwtTokenProvider jwtTokenProvider;

    /** Refresh Token을 Redis에 저장한다 (TTL: refresh-expiration) */
    public void save(Long memberId, String refreshToken) {
        String key = KEY_PREFIX + memberId;
        long expirationMs = jwtTokenProvider.getRefreshExpiration();
        redisTemplate.opsForValue().set(key, refreshToken, expirationMs, TimeUnit.MILLISECONDS);
    }

    /** 저장된 Refresh Token과 일치하는지 검증한다 */
    public boolean validate(Long memberId, String refreshToken) {
        String key = KEY_PREFIX + memberId;
        String stored = redisTemplate.opsForValue().get(key);
        return refreshToken.equals(stored);
    }

    /** Refresh Token을 삭제한다 (로그아웃) */
    public void delete(Long memberId) {
        redisTemplate.delete(KEY_PREFIX + memberId);
    }
}
