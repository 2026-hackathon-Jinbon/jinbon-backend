package com.jinbon.domain.video.service;

import com.jinbon.domain.member.entity.Member;
import com.jinbon.domain.member.repository.MemberRepository;
import com.jinbon.domain.video.dto.SegmentMatchResult;
import com.jinbon.domain.video.dto.VideoVerifyResponse;
import com.jinbon.domain.video.dto.VerificationVerdict;
import com.jinbon.domain.video.entity.Video;
import com.jinbon.domain.video.port.CredentialVerificationPort;
import com.jinbon.domain.video.port.CredentialVerificationPort.Status;
import com.jinbon.domain.video.port.CredentialVerificationPort.VerificationResult;
import com.jinbon.domain.video.port.VideoLedgerPort;
import com.jinbon.domain.video.port.VideoSourcePort;
import com.jinbon.domain.video.port.VerificationCache;
import com.jinbon.domain.video.repository.VideoRepository;
import com.jinbon.global.error.BusinessException;
import com.jinbon.global.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
import java.util.HexFormat;
import java.util.Set;

/**
 * 영상 검증 서비스.
 *
 * 검증 흐름:
 * 1. fineHash(SHA-256) 재계산 → 캐시/DB 정확 매칭 (원본 파일인 경우)
 * 2. 정확 매칭 실패 시, 지각해시(pHash) 생성 → 유사도 검색 (재인코딩 영상 대응)
 * 3. 매칭된 영상에 대해 블록체인 검증 + VC 검증
 * 4. 검증 결과 캐싱 후 반환
 *
 * '진본(authentic)' 규칙: 원본 파일 일치(EXACT_MATCH) 또는 지각해시로 같은 내용임이 확인된 영상
 * (SAME_CONTENT · SIMILAR_MATCH)이 블록체인 서명 재대조와 VC 클레임 결속을 모두 통과하면 진본이다.
 * 플랫폼(YouTube 등)이 재인코딩한 사본은 SIMILAR_MATCH 경로로 진본이 된다.
 * 일부 프레임만 유사한 PARTIAL_MATCH는 진본으로 인정하지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VideoVerifyService {
    private enum BlockchainStatus {
        VERIFIED, INVALID, UNAVAILABLE
    }

    private static final Set<String> ALLOWED_VIDEO_HOSTS = Set.of(
            "youtube.com", "youtu.be", "instagram.com", "tiktok.com",
            "twitter.com", "x.com", "vimeo.com"
    );

    private final VideoRepository videoRepository;
    private final MemberRepository memberRepository;
    private final HashService hashService;
    private final PerceptualHashService perceptualHashService;
    private final VideoFingerprintService videoFingerprintService;
    private final SignatureService signatureService;
    private final VideoLedgerPort videoLedgerPort;
    private final CredentialVerificationPort credentialVerificationPort;
    private final VideoCertificateClaims videoCertificateClaims;
    private final VideoSourcePort videoSourcePort;
    private final VerificationCache verificationCache;

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
        VideoVerifyResponse cached = verificationCache.get(fineHash);
        if (cached != null) {
            log.info("Verify cache HIT - fineHash={}, authentic={}", fineHash.substring(0, 16) + "...", cached.authentic());
            return cached;
        }

        // DB에서 fineHash 정확 매칭
        Video video = videoRepository.findByFineHash(fineHash).orElse(null);
        if (video != null) {
            log.info("Exact match found - videoId={}", video.getId());
            VideoVerifyResponse result = buildVerifyResult(video, VerificationVerdict.EXACT_MATCH, null);
            verificationCache.put(fineHash, result);
            return result;
        }

        // 2. 정확 매칭 실패 → 지각해시 유사도 검색 (재인코딩 영상 대응)
        log.info("No exact match, attempting perceptual hash similarity search");
        String inputFingerprint = generatePerceptualHash(file);
        String inputSegment = generateSegmentFingerprint(file);

        VideoVerifyResponse result = verifyFingerprint(inputFingerprint, inputSegment);
        verificationCache.put(fineHash, result);
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
        VideoVerifyResponse cached = verificationCache.get(urlCacheKey);
        if (cached != null) {
            log.info("Verify cache HIT - authentic={}", cached.authentic());
            return cached;
        }

        Path downloadedFile = videoSourcePort.download(url);
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
                verificationCache.put(urlCacheKey, result);
                return result;
            }

            // 2. 지각해시 + 세그먼트 유사도 검색
            String fingerprint = perceptualHashService.generateFingerprint(downloadedFile);
            String segmentFp = generateSegmentFingerprintFromPath(downloadedFile);

            VideoVerifyResponse result = verifyFingerprint(fingerprint, segmentFp);
            verificationCache.put(urlCacheKey, result);
            return result;

        } catch (IOException e) {
            log.error("Failed to process downloaded video - url={}", url, e);
            throw new BusinessException(ErrorCode.VIDEO_PROCESSING_FAILED);
        } finally {
            videoSourcePort.cleanup(downloadedFile);
        }
    }

    /**
     * 특정 영상의 검증 캐시를 무효화한다.
     * 영상 비활성화 시 호출하여 이전 검증 결과가 반환되지 않도록 한다.
     */
    public void evictCache(Video video) {
        verificationCache.evict(video);
    }

    /**
     * 매칭된 영상에 대해 상태 확인 + 블록체인 검증을 수행하고 결과를 생성한다.
     * 세그먼트 비교 결과가 없는 경우 (EXACT_MATCH 등) 사용한다.
     */
    private VideoVerifyResponse buildVerifyResult(
            Video video,
            VerificationVerdict matchedVerdict,
            Double similarityDistance
    ) {
        return buildVerifyResult(video, matchedVerdict, similarityDistance, null);
    }

    /**
     * 매칭된 영상에 대해 상태 확인 + 블록체인 검증을 수행하고 결과를 생성한다.
     */
    private VideoVerifyResponse buildVerifyResult(
            Video video,
            VerificationVerdict matchedVerdict,
            Double similarityDistance,
            SegmentMatchResult segmentResult
    ) {
        // 비활성화된 영상 확인
        if (!video.isActive()) {
            log.info("Video is deactivated - videoId={}", video.getId());
            return VideoVerifyResponse.deactivated(video.getId(), video.getIssuerDid(), video.getRegisteredAt());
        }

        // 등록자 표시명
        Member registrant = video.getMemberId() == null ? null
                : memberRepository.findById(video.getMemberId()).orElse(null);
        String registrantName = registrant == null ? null : registrant.getPublicName();

        // 블록체인 검증
        BlockchainStatus blockchainStatus = verifyOnBlockchain(video);
        boolean blockchainVerified = blockchainStatus == BlockchainStatus.VERIFIED;

        // VC 확인 — Issuer 발급 원장의 활성 상태 + 등록 당시 클레임 스냅샷 일치 여부
        VerificationResult vcResult = verifyVc(video);
        Status vcStatus = vcResult.status();
        boolean vcVerified = vcStatus == Status.VERIFIED;

        boolean certificateIssued = video.getVcId() != null;
        boolean vcClaimsBound = certificateIssued
                && videoCertificateClaims.matchesSnapshot(video)
                && videoCertificateClaims.matchesCredential(video, vcResult);
        boolean certificateMissing = !certificateIssued;
        boolean verificationUnavailable = blockchainStatus == BlockchainStatus.UNAVAILABLE
                || (certificateIssued && (vcStatus == Status.UNAVAILABLE
                || vcStatus == Status.DISABLED));
        boolean certificateInvalid = certificateIssued
                && (vcStatus == Status.INVALID || !vcClaimsBound);
        boolean contentMatched = matchedVerdict == VerificationVerdict.EXACT_MATCH
                || matchedVerdict == VerificationVerdict.SAME_CONTENT
                || matchedVerdict == VerificationVerdict.SIMILAR_MATCH;
        boolean authentic = contentMatched
                && blockchainVerified && vcVerified && vcClaimsBound
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
        } else if (matchedVerdict == VerificationVerdict.CONTENT_SIMILAR) {
            message = "등록 원본 후보는 찾았지만 대응 구간의 무변조까지 확정하지 못했습니다.";
            notice = buildContentSimilarNotice(segmentResult);
        } else if (matchedVerdict == VerificationVerdict.PARTIAL_MATCH) {
            message = "등록 영상과 일부 프레임이 유사하지만 원본 일치는 확인할 수 없습니다.";
            notice = "영상 길이·순서·구간 차이가 있거나 비교 정보가 부족합니다. 편집 여부를 확정하는 판정은 아닙니다.";
        } else {
            message = "등록된 원본과 같은 내용의 영상입니다. 플랫폼 재인코딩 등으로 파일은 다를 수 있습니다.";
            notice = "영상 길이와 순서대로 추출한 프레임을 비교한 결과입니다.";
        }
        log.info("Video verification completed - videoId={}, authentic={}, blockchainVerified={}, vcVerified={}",
                video.getId(), authentic, blockchainVerified, vcVerified);

        return VideoVerifyResponse.of(verdict, similarityDistance, authentic,
                video.getId(), video.getIssuerDid(), video.getRegisteredAt(), registrantName,
                blockchainVerified, vcVerified, vcClaimsBound, true,
                message, notice, segmentResult);
    }

    private String buildContentSimilarNotice(SegmentMatchResult seg) {
        if (seg == null) {
            return "영상 길이와 순서대로 추출한 프레임을 비교한 결과입니다.";
        }
        return String.format(
                "세그먼트 커버리지 %.0f%%, 원본 대응 구간 %d~%dms, 불일치 구간 %d개.",
                seg.coverage() * 100, seg.matchedStartMs(), seg.matchedEndMs(),
                seg.unmatchedRanges().size());
    }

    /** 세그먼트 비교로 판정을 승격/강등하는 커버리지 임계값 */
    private static final double SEGMENT_COVERAGE_HIGH = 0.95;
    private static final double SEGMENT_COVERAGE_PARTIAL = 0.80;

    /**
     * 활성 영상들 중 지각해시가 유사한 영상을 찾고, 세그먼트 지문으로 정밀 비교한다.
     *
     * <p>검증 2단계: pHash 후보 선별 → 세그먼트 정밀 비교</p>
     * <ul>
     *   <li>pHash similar + 세그먼트 coverage ≥ 95% + 순서 보존 → SIMILAR_MATCH 유지</li>
     *   <li>pHash similar + 세그먼트 coverage < 95% → CONTENT_SIMILAR로 강등</li>
     *   <li>pHash partial + 세그먼트 coverage ≥ 80% + 순서 보존 → CONTENT_SIMILAR + 구간 표시</li>
     *   <li>세그먼트 지문이 없는 원본 → 기존 pHash 판정으로 폴백</li>
     * </ul>
     */
    private VideoVerifyResponse verifyFingerprint(String inputFingerprint, String inputSegment) {
        Video bestVideo = null;
        PerceptualHashService.Comparison best = null;
        for (Video video : videoRepository.findByActiveTrue()) {
            if (video.getPerceptualHash() == null) continue;
            var comparison = perceptualHashService.compare(inputFingerprint, video.getPerceptualHash());
            if (!comparison.similar() && !comparison.partial()) continue;
            if (best == null || (comparison.similar() && !best.similar())
                    || (comparison.similar() == best.similar()
                    && comparison.meanDistance() < best.meanDistance())) {
                best = comparison;
                bestVideo = video;
            }
        }
        if (best == null) return VideoVerifyResponse.notRegistered();
        log.info("Content comparison - videoId={}, mean={}, max={}, matchedRatio={}, durationCompatible={}",
                bestVideo.getId(), best.meanDistance(), best.maxDistance(),
                best.matchedRatio(), best.durationCompatible());

        // 세그먼트 정밀 비교
        SegmentMatchResult segmentResult = compareSegments(inputSegment, bestVideo);
        VerificationVerdict verdict = refineVerdict(best, segmentResult);

        log.info("Segment comparison - videoId={}, coverage={}, orderPreserved={}, gaps={}",
                bestVideo.getId(),
                segmentResult != null ? segmentResult.coverage() : "N/A",
                segmentResult != null ? segmentResult.orderPreserved() : "N/A",
                segmentResult != null ? segmentResult.unmatchedRanges().size() : "N/A");

        return buildVerifyResult(bestVideo, verdict, best.meanDistance(), segmentResult);
    }

    /**
     * pHash 판정과 세그먼트 비교 결과를 종합하여 최종 판정을 결정한다.
     */
    private VerificationVerdict refineVerdict(PerceptualHashService.Comparison pHashResult,
                                               SegmentMatchResult segmentResult) {
        boolean pHashSimilar = pHashResult.similar();

        // 세그먼트 비교 불가 → 기존 pHash 판정으로 폴백
        if (segmentResult == null || segmentResult.totalRefSegments() == 0) {
            return pHashSimilar
                    ? VerificationVerdict.SIMILAR_MATCH
                    : VerificationVerdict.PARTIAL_MATCH;
        }

        double coverage = segmentResult.coverage();
        boolean ordered = segmentResult.orderPreserved();

        if (pHashSimilar) {
            // pHash 유사 + 세그먼트 고커버리지 + 순서 보존 → SIMILAR_MATCH 유지
            if (coverage >= SEGMENT_COVERAGE_HIGH && ordered) {
                return VerificationVerdict.SIMILAR_MATCH;
            }
            // pHash 유사하지만 세그먼트 커버리지 부족 → CONTENT_SIMILAR로 강등
            return VerificationVerdict.CONTENT_SIMILAR;
        }

        // pHash partial + 세그먼트 커버리지 충분 + 순서 보존 → CONTENT_SIMILAR (구간 표시)
        if (coverage >= SEGMENT_COVERAGE_PARTIAL && ordered) {
            return VerificationVerdict.CONTENT_SIMILAR;
        }
        return VerificationVerdict.PARTIAL_MATCH;
    }

    /**
     * 제출 영상의 세그먼트 지문과 원본의 세그먼트 지문을 비교한다.
     * 어느 한쪽이라도 세그먼트 지문이 없으면 null을 반환한다.
     */
    private SegmentMatchResult compareSegments(String inputSegment, Video referenceVideo) {
        if (inputSegment == null || referenceVideo.getSegmentFingerprint() == null) {
            return null;
        }
        return videoFingerprintService.compare(inputSegment, referenceVideo.getSegmentFingerprint());
    }

    /**
     * 블록체인에서 온체인 기록과 비교 검증한다.
     */
    private BlockchainStatus verifyOnBlockchain(Video video) {
        try {
            VideoLedgerPort.Record record = videoLedgerPort.getRecord(video.getMerkleRoot());
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
    private VerificationResult verifyVc(Video video) {
        if (video.getVcId() == null) {
            log.debug("No VC issued for video - videoId={}", video.getId());
            return VerificationResult.disabled();
        }
        return video.getVcCredential() == null
                ? credentialVerificationPort.verify(video.getVcId())
                : credentialVerificationPort.verify(video.getVcId(), video.getVcCredential());
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

    private String generateSegmentFingerprint(MultipartFile file) {
        try {
            return videoFingerprintService.generate(file);
        } catch (IOException e) {
            log.warn("Failed to generate segment fingerprint for verification - fileName={}",
                    file.getOriginalFilename(), e);
            return null;
        }
    }

    private String generateSegmentFingerprintFromPath(Path videoFile) {
        try {
            return videoFingerprintService.generate(videoFile);
        } catch (IOException e) {
            log.warn("Failed to generate segment fingerprint from path", e);
            return null;
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
