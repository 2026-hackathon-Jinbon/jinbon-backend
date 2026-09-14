package com.jinbon.infra.redis;

import com.jinbon.domain.video.dto.VideoVerifyResponse;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;

class RedisVerificationCacheTest {

    @Test
    void storesAndReadsVerificationResultUsingNamespacedKey() {
        RedisTemplate<String, String> redis = mock(RedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        RedisVerificationCache cache = new RedisVerificationCache(redis, new ObjectMapper());
        VideoVerifyResponse result = VideoVerifyResponse.of(
                com.jinbon.domain.video.dto.VerificationVerdict.EXACT_MATCH, null, true,
                null, null, null, true, true, true, true, "원본 일치", null);
        String json = new ObjectMapper().writeValueAsString(result);
        when(values.get("verify:v3:result:fine-hash")).thenReturn(json);

        cache.put("fine-hash", result);
        VideoVerifyResponse loaded = cache.get("fine-hash");

        assertThat(loaded).isEqualTo(result);
        verify(values).set(eq("verify:v3:result:fine-hash"), any(String.class), eq(Duration.ofMinutes(10)));
    }
    @Test
    void doesNotCacheNotRegisteredResultsForFilesOrUrls() {
        RedisTemplate<String, String> redis = mock(RedisTemplate.class);
        RedisVerificationCache cache = new RedisVerificationCache(redis, new ObjectMapper());
        cache.put("fine-hash", VideoVerifyResponse.notRegistered());
        cache.put("url:hash", VideoVerifyResponse.notRegistered());
        org.mockito.Mockito.verifyNoInteractions(redis);
    }

    @Test
    void doesNotKeepSimilarityCandidateAfterAnotherFileCanBeRegistered() {
        RedisTemplate<String, String> redis = mock(RedisTemplate.class);
        RedisVerificationCache cache = new RedisVerificationCache(redis, new ObjectMapper());
        VideoVerifyResponse result = VideoVerifyResponse.of(
                com.jinbon.domain.video.dto.VerificationVerdict.SIMILAR_MATCH, 0.0, false,
                1L, "holder", null, true, true, true, true, "콘텐츠 유사", null);
        cache.put("another-file", result);
        org.mockito.Mockito.verifyNoInteractions(redis);
    }
}
