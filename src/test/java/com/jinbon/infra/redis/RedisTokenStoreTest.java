package com.jinbon.infra.redis;

import com.jinbon.domain.auth.service.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisTokenStoreTest {

    @Test
    void savesRefreshTokenWithConfiguredTtl() {
        RedisTemplate<String, String> redis = mock(RedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        JwtTokenProvider tokens = mock(JwtTokenProvider.class);
        when(redis.opsForValue()).thenReturn(values);
        when(tokens.getRefreshExpiration()).thenReturn(60_000L);

        new RedisRefreshTokenStore(redis, tokens).save(7L, "refresh-token");

        verify(values).set(eq("refresh:7"), eq("refresh-token"), eq(60_000L), eq(java.util.concurrent.TimeUnit.MILLISECONDS));
    }

    @Test
    void consumesDidRebindTokenAtomically() {
        RedisTemplate<String, String> redis = mock(RedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        JwtTokenProvider tokens = mock(JwtTokenProvider.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.getAndDelete("did-rebind:7")).thenReturn("did-token");

        RedisDidRebindTokenStore store = new RedisDidRebindTokenStore(redis, tokens);

        org.assertj.core.api.Assertions.assertThat(store.consume(7L, "did-token")).isTrue();
        org.assertj.core.api.Assertions.assertThat(store.consume(7L, "other-token")).isFalse();
        verify(values, org.mockito.Mockito.times(2)).getAndDelete("did-rebind:7");
    }
}
