package com.jinbon.domain.auth.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class DidRebindTokenService {

    private static final String KEY_PREFIX = "did-rebind:";

    private final RedisTemplate<String, String> redisTemplate;
    private final JwtTokenProvider jwtTokenProvider;

    public void save(Long memberId, String token) {
        redisTemplate.opsForValue().set(
                KEY_PREFIX + memberId, token,
                jwtTokenProvider.getDidRebindExpiration(), TimeUnit.MILLISECONDS);
    }

    public boolean consume(Long memberId, String token) {
        String key = KEY_PREFIX + memberId;
        String stored = redisTemplate.opsForValue().getAndDelete(key);
        return token.equals(stored);
    }
}
