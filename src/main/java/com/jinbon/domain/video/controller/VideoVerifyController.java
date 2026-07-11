package com.jinbon.domain.video.controller;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 영상 검증 API (크롬 확장 등에서 호출 - 인증 불필요)
 *
 * TODO: 해시 기반 캐시 조회 → DB 조회 → 블록체인 검증
 */
@RestController
@RequestMapping("/api/verify")
public class VideoVerifyController {

}
