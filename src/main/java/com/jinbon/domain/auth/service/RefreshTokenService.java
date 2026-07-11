package com.jinbon.domain.auth.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private static final String KEY_PREFIX = "refresh:";

    private final RedisTemplate<String, String> redisTemplate;
    private final JwtTokenProvider jwtTokenProvider;

    public void save(Long memberId, String refreshToken) {
        String key = KEY_PREFIX + memberId;
        long expirationMs = jwtTokenProvider.getRefreshExpiration();
        redisTemplate.opsForValue().set(key, refreshToken, expirationMs, TimeUnit.MILLISECONDS);
    }

    public boolean validate(Long memberId, String refreshToken) {
        String key = KEY_PREFIX + memberId;
        String stored = redisTemplate.opsForValue().get(key);
        return refreshToken.equals(stored);
    }

    public void delete(Long memberId) {
        redisTemplate.delete(KEY_PREFIX + memberId);
    }
}
