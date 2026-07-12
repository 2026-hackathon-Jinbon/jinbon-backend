package com.jinbon.domain.video.service;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.jinbon.domain.video.dto.VideoVerifyResponse;
import com.jinbon.domain.video.entity.Video;
import com.jinbon.domain.video.repository.VideoRepository;
import com.jinbon.global.error.BusinessException;
import com.jinbon.global.error.ErrorCode;
import com.jinbon.infra.blockchain.ContractEncoder;
import com.jinbon.infra.blockchain.OmniOneChainClient;
import com.jinbon.infra.download.VideoDownloadService;
import com.jinbon.infra.opendid.VcVerificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * 영상 검증 서비스.
 *
 * 검증 흐름:
 * 1. fineHash(SHA-256) 재계산 → 캐시/DB 정확 매칭 (원본 파일인 경우)
 * 2. 정확 매칭 실패 시, 지각해시(pHash) 생성 → 유사도 검색 (재인코딩 영상 대응)
 * 3. 매칭된 영상에 대해 블록체인 검증
 * 4. 검증 결과 캐싱 후 반환
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VideoVerifyService {

    private static final String VERIFY_CACHE_KEY_PREFIX = "verify:result:";
    private static final Duration CACHE_TTL = Duration.ofMinutes(10);

    private final VideoRepository videoRepository;
    private final HashService hashService;
    private final PerceptualHashService perceptualHashService;
    private final SignatureService signatureService;
    private final OmniOneChainClient omniOneChainClient;
    private final VcVerificationService vcVerificationService;
    private final VideoDownloadService videoDownloadService;
    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 영상 파일을 검증한다.
     * fineHash 정확 매칭 → 지각해시 유사도 매칭 → 블록체인 검증 순서로 처리한다.
     */
    @Transactional(readOnly = true)
    public VideoVerifyResponse verify(MultipartFile file) {
        log.info("Video verification started - fileName={}, fileSize={}bytes",
                file.getOriginalFilename(), file.getSize());

        // 1. fineHash 재계산 → 캐시/DB 정확 매칭 시도
        String fineHash = generateFineHash(file);
        log.debug("Fine hash recalculated - fineHash={}", fineHash.substring(0, 16) + "...");

        // 캐시에서 검증 결과 조회
        VideoVerifyResponse cached = getCachedResult(fineHash);
        if (cached != null) {
            log.info("Verify cache HIT - fineHash={}, authentic={}", fineHash.substring(0, 16) + "...", cached.authentic());
            return cached;
        }

        // DB에서 fineHash 정확 매칭
        Video video = videoRepository.findByFineHash(fineHash).orElse(null);
        if (video != null) {
            log.info("Exact match found - videoId={}", video.getId());
            VideoVerifyResponse result = buildVerifyResult(video);
            cacheResult(fineHash, result);
            return result;
        }

        // 2. 정확 매칭 실패 → 지각해시 유사도 검색 (재인코딩 영상 대응)
        log.info("No exact match, attempting perceptual hash similarity search");
        String inputFingerprint = generatePerceptualHash(file);

        Video similarVideo = findSimilarVideo(inputFingerprint);
        if (similarVideo == null) {
            log.info("No similar video found");
            VideoVerifyResponse result = VideoVerifyResponse.notRegistered();
            cacheResult(fineHash, result);
            return result;
        }

        double distance = perceptualHashService.compareFingerprints(inputFingerprint, similarVideo.getPerceptualHash());
        log.info("Similar video found - videoId={}, hammingDistance={}", similarVideo.getId(), String.format("%.1f", distance));

        VideoVerifyResponse result = buildVerifyResult(similarVideo);
        cacheResult(fineHash, result);
        return result;
    }

    /**
     * URL에서 영상을 다운로드하여 검증한다.
     * yt-dlp로 영상을 다운로드한 후, fineHash 정확 매칭 → pHash 유사도 검색 순서로 처리한다.
     * 서버가 전체 영상을 보유하므로 밀도 높은 프레임 분석이 가능하다.
     */
    @Transactional(readOnly = true)
    public VideoVerifyResponse verifyByUrl(String url) {
        log.info("URL-based verification started - url={}", url);

        // 캐시 조회 (URL을 키로 사용)
        VideoVerifyResponse cached = getCachedResult(url);
        if (cached != null) {
            log.info("Verify cache HIT - url={}, authentic={}", url, cached.authentic());
            return cached;
        }

        Path downloadedFile = videoDownloadService.download(url);
        try {
            // 1. fineHash로 정확 매칭 시도
            String fineHash;
            try (FileInputStream fis = new FileInputStream(downloadedFile.toFile())) {
                fineHash = hashService.generateFineHash(fis);
            }

            Video video = videoRepository.findByFineHash(fineHash).orElse(null);
            if (video != null) {
                log.info("Exact match found from URL - videoId={}", video.getId());
                VideoVerifyResponse result = buildVerifyResult(video);
                cacheResult(url, result);
                return result;
            }

            // 2. 지각해시 유사도 검색
            String fingerprint = perceptualHashService.generateFingerprint(downloadedFile);

            Video similarVideo = findSimilarVideo(fingerprint);
            if (similarVideo == null) {
                log.info("No similar video found for URL - url={}", url);
                VideoVerifyResponse result = VideoVerifyResponse.notRegistered();
                cacheResult(url, result);
                return result;
            }

            double distance = perceptualHashService.compareFingerprints(fingerprint, similarVideo.getPerceptualHash());
            log.info("Similar video found from URL - videoId={}, hammingDistance={}",
                    similarVideo.getId(), String.format("%.1f", distance));

            VideoVerifyResponse result = buildVerifyResult(similarVideo);
            cacheResult(url, result);
            return result;

        } catch (IOException e) {
            log.error("Failed to process downloaded video - url={}", url, e);
            throw new BusinessException(ErrorCode.VIDEO_PROCESSING_FAILED);
        } finally {
            videoDownloadService.cleanup(downloadedFile);
        }
    }

    /**
     * 특정 영상의 검증 캐시를 무효화한다.
     * 영상 비활성화 시 호출하여 이전 검증 결과가 반환되지 않도록 한다.
     */
    public void evictCache(String fineHash) {
        redisTemplate.delete(VERIFY_CACHE_KEY_PREFIX + fineHash);
        log.debug("Verify cache evicted - fineHash={}", fineHash.substring(0, 16) + "...");
    }

    /**
     * 매칭된 영상에 대해 상태 확인 + 블록체인 검증을 수행하고 결과를 생성한다.
     */
    private VideoVerifyResponse buildVerifyResult(Video video) {
        // 비활성화된 영상 확인
        if (!video.isActive()) {
            log.info("Video is deactivated - videoId={}", video.getId());
            return VideoVerifyResponse.deactivated(video.getId(), video.getIssuerDid(), video.getRegisteredAt());
        }

        // 블록체인 검증
        boolean blockchainVerified = verifyOnBlockchain(video);

        // VC 검증 — 상태(active) + 서명 무결성 확인
        boolean vcVerified = verifyVc(video);

        log.info("Video verification completed - videoId={}, authentic=true, blockchainVerified={}, vcVerified={}",
                video.getId(), blockchainVerified, vcVerified);

        return new VideoVerifyResponse(true, video.getId(), video.getIssuerDid(),
                video.getRegisteredAt(), blockchainVerified, vcVerified, true,
                "Video is authentic.");
    }

    /**
     * 활성 영상들 중 지각해시가 유사한 영상을 찾는다.
     * MVP에서는 전체 활성 영상을 로드하여 in-memory 비교한다.
     */
    private Video findSimilarVideo(String inputFingerprint) {
        List<Video> activeVideos = videoRepository.findByActiveTrue();

        Video bestMatch = null;
        double bestDistance = PerceptualHashService.SIMILARITY_THRESHOLD;

        for (Video video : activeVideos) {
            if (video.getPerceptualHash() == null) continue;

            double distance = perceptualHashService.compareFingerprints(inputFingerprint, video.getPerceptualHash());
            if (distance < bestDistance) {
                bestDistance = distance;
                bestMatch = video;
            }
        }

        return bestMatch;
    }

    /**
     * 블록체인에서 온체인 기록과 비교 검증한다.
     */
    private boolean verifyOnBlockchain(Video video) {
        try {
            String callData = ContractEncoder.encodeGetRecord(video.getMerkleRoot());
            String result = omniOneChainClient.ethCall(callData);

            if (result == null || result.equals("0x") || result.equals("0x0")) {
                log.warn("No blockchain record found - videoId={}, merkleRoot={}",
                        video.getId(), video.getMerkleRoot());
                return false;
            }

            // 서명 재계산으로 무결성 확인
            String recalculatedSignature = signatureService.sign(video.getIssuerDid() + video.getMerkleRoot());
            if (!recalculatedSignature.equals(video.getSignature())) {
                log.warn("Signature mismatch - videoId={}", video.getId());
                return false;
            }

            log.debug("Blockchain verification passed - videoId={}", video.getId());
            return true;
        } catch (Exception e) {
            log.warn("Blockchain verification failed - videoId={}, reason={}", video.getId(), e.getMessage());
            return false;
        }
    }

    /**
     * VC의 상태(active/revoked/expired)와 서명 무결성을 검증한다.
     * vcId가 없는 경우(VC 미발급) false를 반환한다.
     */
    private boolean verifyVc(Video video) {
        if (video.getVcId() == null) {
            log.debug("No VC issued for video - videoId={}", video.getId());
            return false;
        }
        return vcVerificationService.verify(video.getVcId());
    }

    private VideoVerifyResponse getCachedResult(String fineHash) {
        String json = redisTemplate.opsForValue().get(VERIFY_CACHE_KEY_PREFIX + fineHash);
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, VideoVerifyResponse.class);
        } catch (JacksonException e) {
            log.warn("Failed to deserialize cached verify result, ignoring cache");
            return null;
        }
    }

    private void cacheResult(String fineHash, VideoVerifyResponse result) {
        try {
            String json = objectMapper.writeValueAsString(result);
            redisTemplate.opsForValue().set(VERIFY_CACHE_KEY_PREFIX + fineHash, json, CACHE_TTL);
            log.debug("Verify result cached - fineHash={}, ttl={}min", fineHash.substring(0, 16) + "...", CACHE_TTL.toMinutes());
        } catch (JacksonException e) {
            log.warn("Failed to cache verify result");
        }
    }

    private String generateFineHash(MultipartFile file) {
        try {
            return hashService.generateFineHash(file.getInputStream());
        } catch (IOException e) {
            log.error("Failed to generate fine hash - fileName={}", file.getOriginalFilename(), e);
            throw new BusinessException(ErrorCode.VIDEO_PROCESSING_FAILED);
        }
    }

    private String generatePerceptualHash(MultipartFile file) {
        try {
            return perceptualHashService.generateFingerprint(file);
        } catch (IOException e) {
            log.error("Failed to generate perceptual hash - fileName={}", file.getOriginalFilename(), e);
            throw new BusinessException(ErrorCode.VIDEO_PROCESSING_FAILED);
        }
    }
}
