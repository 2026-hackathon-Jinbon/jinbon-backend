package com.jinbon.domain.auth.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * DID 재바인딩 1회용 토큰 관리 서비스.
 *
 * 로그인 성공 시 발급되며, DID 재바인딩 API에서 1회 소비된다.
 * Redis의 getAndDelete 원자 연산으로 동시 사용을 방지한다.
 */
@Service
@RequiredArgsConstructor
public class DidRebindTokenService {

    private static final String KEY_PREFIX = "did-rebind:";

    private final RedisTemplate<String, String> redisTemplate;
    private final JwtTokenProvider jwtTokenProvider;

    /** DID 재바인딩 토큰을 Redis에 저장한다 (TTL: 10분) */
    public void save(Long memberId, String token) {
        redisTemplate.opsForValue().set(
                KEY_PREFIX + memberId, token,
                jwtTokenProvider.getDidRebindExpiration(), TimeUnit.MILLISECONDS);
    }

    /**
     * 토큰을 1회 소비한다.
     * getAndDelete 원자 연산으로 동시 요청에 의한 이중 사용을 방지한다.
     *
     * @return 토큰이 유효하고 일치하면 true, 만료/불일치/이미 소비되었으면 false
     */
    public boolean consume(Long memberId, String token) {
        String key = KEY_PREFIX + memberId;
        String stored = redisTemplate.opsForValue().getAndDelete(key);
        return token.equals(stored);
    }
}
