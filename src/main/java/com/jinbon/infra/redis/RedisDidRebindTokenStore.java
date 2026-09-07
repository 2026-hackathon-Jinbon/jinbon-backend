package com.jinbon.infra.redis;

import com.jinbon.domain.auth.port.DidRebindTokenStore;
import com.jinbon.domain.auth.service.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/** Redis 기반 DID 재연결 토큰 저장소. */
@Service
@RequiredArgsConstructor
public class RedisDidRebindTokenStore implements DidRebindTokenStore {

    private static final String KEY_PREFIX = "did-rebind:";

    private final RedisTemplate<String, String> redisTemplate;
    private final JwtTokenProvider jwtTokenProvider;

    @Override
    public void save(Long memberId, String token) {
        redisTemplate.opsForValue().set(
                KEY_PREFIX + memberId,
                token,
                jwtTokenProvider.getDidRebindExpiration(),
                TimeUnit.MILLISECONDS);
    }

    @Override
    public boolean consume(Long memberId, String token) {
        String stored = redisTemplate.opsForValue().getAndDelete(KEY_PREFIX + memberId);
        return token.equals(stored);
    }
}
