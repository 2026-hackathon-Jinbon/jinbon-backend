package com.jinbon.infra.redis;

import com.jinbon.domain.auth.port.RefreshTokenStore;
import com.jinbon.domain.auth.service.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;

/** Redis 기반 Refresh Token 저장소. */
@Service
@RequiredArgsConstructor
public class RedisRefreshTokenStore implements RefreshTokenStore {

    private static final String KEY_PREFIX = "refresh:";

    private final RedisTemplate<String, String> redisTemplate;
    private final JwtTokenProvider jwtTokenProvider;

    @Override
    public void save(Long memberId, String refreshToken) {
        redisTemplate.opsForValue().set(
                KEY_PREFIX + memberId,
                refreshToken,
                jwtTokenProvider.getRefreshExpiration(),
                TimeUnit.MILLISECONDS);
    }

    @Override
    public boolean rotate(Long memberId, String oldToken, String newToken) {
        String script = """
                if redis.call('get', KEYS[1]) == ARGV[1] then
                    redis.call('psetex', KEYS[1], ARGV[3], ARGV[2])
                    return 1
                end
                return 0
                """;
        DefaultRedisScript<Long> redisScript = new DefaultRedisScript<>(script, Long.class);
        Long result = redisTemplate.execute(redisScript, List.of(KEY_PREFIX + memberId),
                oldToken, newToken, String.valueOf(jwtTokenProvider.getRefreshExpiration()));
        return Long.valueOf(1L).equals(result);
    }

    @Override
    public void delete(Long memberId) {
        redisTemplate.delete(KEY_PREFIX + memberId);
    }
}
