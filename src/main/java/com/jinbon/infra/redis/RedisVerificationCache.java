package com.jinbon.infra.redis;

import com.jinbon.domain.video.dto.VerificationVerdict;
import com.jinbon.domain.video.dto.VideoVerifyResponse;
import com.jinbon.domain.video.entity.Video;
import com.jinbon.domain.video.port.VerificationCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/** Redis 기반 영상 검증 결과 캐시. */
@Slf4j
@Service
@RequiredArgsConstructor
public class RedisVerificationCache implements VerificationCache {

    private static final String RESULT_KEY_PREFIX = "verify:v2:result:";
    private static final String VIDEO_INDEX_KEY_PREFIX = "verify:v2:video:";
    private static final Duration TTL = Duration.ofMinutes(10);

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public VideoVerifyResponse get(String key) {
        String resultKey = RESULT_KEY_PREFIX + key;
        String json = redisTemplate.opsForValue().get(resultKey);
        if (json == null) {
            return null;
        }
        try {
            VideoVerifyResponse result = objectMapper.readValue(json, VideoVerifyResponse.class);
            if (result.verdict() == null) {
                redisTemplate.delete(resultKey);
                return null;
            }
            return result;
        } catch (JacksonException e) {
            log.warn("Failed to deserialize cached verify result, ignoring cache");
            return null;
        }
    }

    @Override
    public void put(String key, VideoVerifyResponse result) {
        if (result.verdict() == VerificationVerdict.VERIFICATION_UNAVAILABLE) {
            return;
        }
        try {
            String resultKey = RESULT_KEY_PREFIX + key;
            redisTemplate.opsForValue().set(resultKey, objectMapper.writeValueAsString(result), TTL);
            if (result.videoId() != null) {
                String indexKey = VIDEO_INDEX_KEY_PREFIX + result.videoId();
                redisTemplate.opsForSet().add(indexKey, resultKey);
                redisTemplate.expire(indexKey, TTL);
            }
        } catch (JacksonException e) {
            log.warn("Failed to cache verify result");
        }
    }

    @Override
    public void evict(Video video) {
        String indexKey = VIDEO_INDEX_KEY_PREFIX + video.getId();
        Set<String> indexedKeys = redisTemplate.opsForSet().members(indexKey);
        if (indexedKeys != null && !indexedKeys.isEmpty()) {
            redisTemplate.delete(indexedKeys);
        }
        redisTemplate.delete(List.of(RESULT_KEY_PREFIX + video.getFineHash(), indexKey));
        log.debug("Verify caches evicted - videoId={}, indexedKeyCount={}",
                video.getId(), indexedKeys != null ? indexedKeys.size() : 0);
    }
}
