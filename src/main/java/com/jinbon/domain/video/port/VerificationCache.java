package com.jinbon.domain.video.port;

import com.jinbon.domain.video.dto.VideoVerifyResponse;
import com.jinbon.domain.video.entity.Video;

/** 영상 검증 결과 캐시 경계. 캐시 기술과 키 구조는 구현체가 담당한다. */
public interface VerificationCache {

    VideoVerifyResponse get(String key);

    void put(String key, VideoVerifyResponse result);

    void evict(Video video);
}
