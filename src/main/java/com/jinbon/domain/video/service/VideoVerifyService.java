package com.jinbon.domain.video.service;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.jinbon.domain.video.dto.VideoVerifyResponse;
import com.jinbon.domain.video.dto.VerificationVerdict;
import com.jinbon.domain.video.entity.Video;
import com.jinbon.domain.video.repository.VideoRepository;
import com.jinbon.global.error.BusinessException;
import com.jinbon.global.error.ErrorCode;
import com.jinbon.infra.blockchain.ContractEncoder;
import com.jinbon.infra.blockchain.ContractDecoder;
import com.jinbon.infra.blockchain.OmniOneChainClient;
import com.jinbon.infra.download.VideoDownloadService;
import com.jinbon.infra.opendid.VcVerificationService;
import com.jinbon.infra.opendid.VcVerificationService.VerificationStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

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
    private enum BlockchainStatus {
        VERIFIED, INVALID, UNAVAILABLE
    }

    private static final String VERIFY_CACHE_KEY_PREFIX = "verify:v2:result:";
    private static final String VIDEO_CACHE_INDEX_PREFIX = "verify:v2:video:";
    private static final Duration CACHE_TTL = Duration.ofMinutes(10);
    private static final Set<String> ALLOWED_VIDEO_HOSTS = Set.of(
            "youtube.com", "youtu.be", "instagram.com", "tiktok.com",
            "twitter.com", "x.com", "vimeo.com"
    );

    private final VideoRepository videoRepository;
    private final HashService hashService;
    private final PerceptualHashService perceptualHashService;
    private final SignatureService signatureService;
    private final OmniOneChainClient omniOneChainClient;
    private final VcVerificationService vcVerificationService;
    private final VideoCertificateClaims videoCertificateClaims;
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
            VideoVerifyResponse result = buildVerifyResult(video, VerificationVerdict.EXACT_MATCH, null);
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

        VerificationVerdict matchedVerdict = distance == 0.0
                ? VerificationVerdict.SAME_CONTENT
                : VerificationVerdict.SIMILAR_MATCH;
        VideoVerifyResponse result = buildVerifyResult(similarVideo, matchedVerdict, distance);
        cacheResult(fineHash, result);
        return result;
    }

    /**
     * URL에서 영상을 다운로드하여 검증한다.
     * yt-dlp로 영상을 다운로드한 후, fineHash 정확 매칭 → pHash 유사도 검색 순서로 처리한다.
     * 서버가 전체 영상을 보유하므로 밀도 높은 프레임 분석이 가능하다.
     *
     * SSRF 방어: HTTPS 스킴만 허용하고, 내부 네트워크 IP 대역을 차단한다.
     */
    @Transactional(readOnly = true)
    public VideoVerifyResponse verifyByUrl(String url) {
        log.info("URL-based verification started - url={}", url);

        // SSRF 방어: URL 스킴 및 호스트 검증
        validateUrl(url);

        // 캐시 조회 (URL을 SHA-256 해시로 변환하여 키로 사용 — 악의적 긴 URL 방어)
        String urlCacheKey = hashUrlForCacheKey(url);
        VideoVerifyResponse cached = getCachedResult(urlCacheKey);
        if (cached != null) {
            log.info("Verify cache HIT - authentic={}", cached.authentic());
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
                VideoVerifyResponse result = buildVerifyResult(
                        video, VerificationVerdict.EXACT_MATCH, null);
                cacheResult(urlCacheKey, result);
                return result;
            }

            // 2. 지각해시 유사도 검색
            String fingerprint = perceptualHashService.generateFingerprint(downloadedFile);

            Video similarVideo = findSimilarVideo(fingerprint);
            if (similarVideo == null) {
                log.info("No similar video found for URL");
                VideoVerifyResponse result = VideoVerifyResponse.notRegistered();
                cacheResult(urlCacheKey, result);
                return result;
            }

            double distance = perceptualHashService.compareFingerprints(fingerprint, similarVideo.getPerceptualHash());
            log.info("Similar video found from URL - videoId={}, hammingDistance={}",
                    similarVideo.getId(), String.format("%.1f", distance));

            VerificationVerdict matchedVerdict = distance == 0.0
                    ? VerificationVerdict.SAME_CONTENT
                    : VerificationVerdict.SIMILAR_MATCH;
            VideoVerifyResponse result = buildVerifyResult(similarVideo, matchedVerdict, distance);
            cacheResult(urlCacheKey, result);
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
    public void evictCache(Video video) {
        String indexKey = VIDEO_CACHE_INDEX_PREFIX + video.getId();
        Set<String> indexedKeys = redisTemplate.opsForSet().members(indexKey);
        if (indexedKeys != null && !indexedKeys.isEmpty()) {
            redisTemplate.delete(indexedKeys);
        }
        redisTemplate.delete(List.of(VERIFY_CACHE_KEY_PREFIX + video.getFineHash(), indexKey));
        log.debug("Verify caches evicted - videoId={}, indexedKeyCount={}",
                video.getId(), indexedKeys != null ? indexedKeys.size() : 0);
    }

    /**
     * 매칭된 영상에 대해 상태 확인 + 블록체인 검증을 수행하고 결과를 생성한다.
     */
    private VideoVerifyResponse buildVerifyResult(
            Video video,
            VerificationVerdict matchedVerdict,
            Double similarityDistance
    ) {
        // 비활성화된 영상 확인
        if (!video.isActive()) {
            log.info("Video is deactivated - videoId={}", video.getId());
            return VideoVerifyResponse.deactivated(video.getId(), video.getIssuerDid(), video.getRegisteredAt());
        }

        // 블록체인 검증
        BlockchainStatus blockchainStatus = verifyOnBlockchain(video);
        boolean blockchainVerified = blockchainStatus == BlockchainStatus.VERIFIED;

        // VC 확인 — Issuer 발급 원장의 활성 상태 + 등록 당시 클레임 스냅샷 일치 여부
        VerificationStatus vcStatus = verifyVc(video);
        boolean vcVerified = vcStatus == VerificationStatus.VERIFIED;

        boolean certificateIssued = video.getVcId() != null;
        boolean vcClaimsBound = certificateIssued && videoCertificateClaims.matchesSnapshot(video);
        boolean certificateMissing = !certificateIssued;
        boolean verificationUnavailable = blockchainStatus == BlockchainStatus.UNAVAILABLE
                || (certificateIssued && (vcStatus == VerificationStatus.UNAVAILABLE
                || vcStatus == VerificationStatus.DISABLED));
        boolean certificateInvalid = certificateIssued
                && (vcStatus == VerificationStatus.INVALID || !vcClaimsBound);
        boolean authentic = blockchainVerified && vcVerified && vcClaimsBound
                && !verificationUnavailable && !certificateInvalid;
        VerificationVerdict verdict = verificationUnavailable
                ? VerificationVerdict.VERIFICATION_UNAVAILABLE : matchedVerdict;
        String message;
        String notice = null;
        if (verificationUnavailable) {
            message = "외부 검증 서비스에 연결할 수 없어 현재 진본 여부를 확인할 수 없습니다.";
            notice = "잠시 후 다시 검증해 주세요.";
        } else if (!blockchainVerified) {
            verdict = VerificationVerdict.VERIFICATION_UNAVAILABLE;
            message = "등록 기록은 찾았지만 블록체인 무결성 검증을 통과하지 못했습니다.";
            notice = "운영자 확인이 필요합니다.";
        } else if (certificateMissing) {
            verdict = VerificationVerdict.CERTIFICATE_MISSING;
            message = "블록체인 등록 기록은 확인했지만 신원 기반 VC 보증서가 발급되지 않았습니다.";
            notice = "보증서 발급 전에는 진본 인증 완료로 판단하지 않습니다.";
        } else if (certificateInvalid) {
            verdict = VerificationVerdict.CERTIFICATE_INVALID;
            message = "영상의 블록체인 등록 기록은 확인했지만 VC 보증서가 유효하지 않습니다.";
            notice = "보증서가 폐기·만료되었거나 등록 당시 정보와 일치하지 않습니다.";
        } else if (matchedVerdict == VerificationVerdict.EXACT_MATCH) {
            message = "등록된 원본 파일과 정확히 일치합니다.";
        } else if (matchedVerdict == VerificationVerdict.SAME_CONTENT) {
            message = "등록된 영상과 동일한 콘텐츠로 판단됩니다.";
            notice = "영상 프레임을 비교한 결과이며, 파일의 바이트가 동일하다는 의미는 아닙니다.";
        } else {
            message = "등록 영상과 유사합니다. 재인코딩 또는 일부 변환되었을 수 있습니다.";
            notice = "유사 일치는 원본 파일과 바이트 단위로 동일하다는 의미가 아닙니다.";
        }
        log.info("Video verification completed - videoId={}, authentic={}, blockchainVerified={}, vcVerified={}",
                video.getId(), authentic, blockchainVerified, vcVerified);

        return new VideoVerifyResponse(verdict, similarityDistance, authentic,
                video.getId(), video.getIssuerDid(), video.getRegisteredAt(),
                blockchainVerified, vcVerified, vcClaimsBound, true, message, notice);
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
    private BlockchainStatus verifyOnBlockchain(Video video) {
        try {
            String callData = ContractEncoder.encodeGetRecord(video.getMerkleRoot());
            String result = omniOneChainClient.ethCall(callData);

            ContractDecoder.VideoRecord record = ContractDecoder.decodeGetRecord(result);
            if (!record.registered() || !record.active()
                    || !video.getIssuerDid().equals(record.issuerDid())) {
                log.warn("No blockchain record found - videoId={}, merkleRoot={}",
                        video.getId(), video.getMerkleRoot());
                return BlockchainStatus.INVALID;
            }

            // 서명 재계산으로 무결성 확인
            String recalculatedSignature = signatureService.sign(video.getIssuerDid() + video.getMerkleRoot());
            if (!recalculatedSignature.equals(video.getSignature())
                    || !recalculatedSignature.equals(record.signature())) {
                log.warn("Signature mismatch - videoId={}", video.getId());
                return BlockchainStatus.INVALID;
            }

            log.debug("Blockchain verification passed - videoId={}", video.getId());
            return BlockchainStatus.VERIFIED;
        } catch (Exception e) {
            log.warn("Blockchain verification failed - videoId={}, reason={}", video.getId(), e.getMessage());
            return BlockchainStatus.UNAVAILABLE;
        }
    }

    /**
     * VC의 상태(active/revoked/expired)와 서명 무결성을 검증한다.
     * vcId가 없는 경우(VC 미발급) false를 반환한다.
     */
    private VerificationStatus verifyVc(Video video) {
        if (video.getVcId() == null) {
            log.debug("No VC issued for video - videoId={}", video.getId());
            return VerificationStatus.DISABLED;
        }
        return vcVerificationService.verifyStatus(video.getVcId());
    }

    private VideoVerifyResponse getCachedResult(String fineHash) {
        String json = redisTemplate.opsForValue().get(VERIFY_CACHE_KEY_PREFIX + fineHash);
        if (json == null) {
            return null;
        }
        try {
            VideoVerifyResponse result = objectMapper.readValue(json, VideoVerifyResponse.class);
            if (result.verdict() == null) {
                redisTemplate.delete(VERIFY_CACHE_KEY_PREFIX + fineHash);
                return null;
            }
            return result;
        } catch (JacksonException e) {
            log.warn("Failed to deserialize cached verify result, ignoring cache");
            return null;
        }
    }

    private void cacheResult(String fineHash, VideoVerifyResponse result) {
        if (result.verdict() == VerificationVerdict.VERIFICATION_UNAVAILABLE) {
            return;
        }
        try {
            String resultKey = VERIFY_CACHE_KEY_PREFIX + fineHash;
            String json = objectMapper.writeValueAsString(result);
            redisTemplate.opsForValue().set(resultKey, json, CACHE_TTL);
            if (result.videoId() != null) {
                String indexKey = VIDEO_CACHE_INDEX_PREFIX + result.videoId();
                redisTemplate.opsForSet().add(indexKey, resultKey);
                redisTemplate.expire(indexKey, CACHE_TTL);
            }
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

    // ── SSRF 방어 ──────────────────────────────────────────────

    /**
     * URL의 스킴과 호스트를 검증하여 SSRF 공격을 차단한다.
     * - HTTPS 스킴만 허용 (HTTP 차단)
     * - 내부 네트워크 IP 대역 (10.x, 172.16-31.x, 192.168.x, 127.x, 169.254.x) 차단
     * - IPv6 루프백/링크로컬 차단
     */
    private void validateUrl(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.VIDEO_DOWNLOAD_FAILED);
        }

        // HTTPS만 허용
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            log.warn("URL scheme rejected (HTTPS only) - scheme={}", uri.getScheme());
            throw new BusinessException(ErrorCode.VIDEO_DOWNLOAD_FAILED);
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new BusinessException(ErrorCode.VIDEO_DOWNLOAD_FAILED);
        }
        String normalizedHost = host.toLowerCase(java.util.Locale.ROOT);
        if (uri.getUserInfo() != null || uri.getPort() != -1 || ALLOWED_VIDEO_HOSTS.stream()
                .noneMatch(allowed -> normalizedHost.equals(allowed) || normalizedHost.endsWith("." + allowed))) {
            log.warn("Video host rejected - host={}", normalizedHost);
            throw new BusinessException(ErrorCode.VIDEO_DOWNLOAD_FAILED);
        }

        // DNS 응답 전체를 확인하여 혼합 public/private 응답도 차단한다.
        try {
            InetAddress[] addresses = InetAddress.getAllByName(normalizedHost);
            if (addresses.length == 0) {
                throw new BusinessException(ErrorCode.VIDEO_DOWNLOAD_FAILED);
            }
            for (InetAddress address : addresses) {
                if (address.isLoopbackAddress() || address.isSiteLocalAddress()
                        || address.isLinkLocalAddress() || address.isAnyLocalAddress()
                        || address.isMulticastAddress()) {
                    log.warn("Internal network access blocked - host={}, ip={}", host, address.getHostAddress());
                    throw new BusinessException(ErrorCode.VIDEO_DOWNLOAD_FAILED);
                }
            }
        } catch (UnknownHostException e) {
            log.warn("DNS resolution failed - host={}", host);
            throw new BusinessException(ErrorCode.VIDEO_DOWNLOAD_FAILED);
        }
    }

    /**
     * URL을 SHA-256 해시로 변환하여 Redis 캐시 키로 사용한다.
     * 악의적으로 긴 URL이 Redis 메모리를 낭비하는 것을 방지한다.
     */
    private String hashUrlForCacheKey(String url) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(url.getBytes(StandardCharsets.UTF_8));
            return "url:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
