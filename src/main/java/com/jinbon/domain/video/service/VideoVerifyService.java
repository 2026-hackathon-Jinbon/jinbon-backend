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
 * 2. 정확 매칭 실패 시, 영상 pHash 후보 검색 → 영상·음성 세그먼트 정밀 비교
 * 3. 매칭된 영상에 대해 블록체인 검증 + VC 검증
 * 4. 검증 결과 캐싱 후 반환
 *
 * '진본(authentic)' 규칙: 원본 파일 일치(EXACT_MATCH) 또는 영상·음성 세그먼트가
 * 순서대로 확인된 SIMILAR_MATCH가 블록체인 서명 재대조와 VC 클레임 결속을 모두 통과하면 진본이다.
 * 플랫폼(YouTube 등)이 재인코딩한 사본과 등록 원본의 연속 쇼츠 구간은 SIMILAR_MATCH 경로로 진본이 된다.
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
    private final MediaFingerprintService mediaFingerprintService;
    private final VideoContentMatchService contentMatchService;
    private final SignatureService signatureService;
    private final VideoLedgerPort videoLedgerPort;
    private final CredentialVerificationPort credentialVerificationPort;
    private final VideoCertificateClaims videoCertificateClaims;
    private final VideoSourcePort videoSourcePort;
    private final VerificationCache verificationCache;

    /**
     * 영상 파일을 검증한다.
     * fineHash 정확 매칭 → 영상·음성 콘텐츠 매칭 → 블록체인 검증 순서로 처리한다.
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
        MediaFingerprintService.Fingerprints fingerprints = generateFingerprints(file);

        VideoVerifyResponse result = verifyFingerprint(
                fingerprints.perceptual(), fingerprints.segment(), fingerprints.audio());
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

            // 2. 지각해시 + 세그먼트 + 음성 유사도 검색
            MediaFingerprintService.Fingerprints fingerprints = generateFingerprints(downloadedFile);

            VideoVerifyResponse result = verifyFingerprint(
                    fingerprints.perceptual(), fingerprints.segment(), fingerprints.audio());
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
     * 세그먼트·음성 비교 결과가 없는 경우 (EXACT_MATCH 등) 사용한다.
     */
    private VideoVerifyResponse buildVerifyResult(
            Video video,
            VerificationVerdict matchedVerdict,
            Double similarityDistance
    ) {
        return buildVerifyResult(video, matchedVerdict, similarityDistance, null, null);
    }

    /**
     * 매칭된 영상에 대해 상태 확인 + 블록체인 검증을 수행하고 결과를 생성한다.
     */
    private VideoVerifyResponse buildVerifyResult(
            Video video,
            VerificationVerdict matchedVerdict,
            Double similarityDistance,
            SegmentMatchResult segmentResult,
            SegmentMatchResult audioResult
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
            message = "등록 원본 후보는 찾았지만 영상·음성 비교 기준을 충족하지 못해 진본으로 인증하지 않습니다.";
            notice = buildContentSimilarNotice(segmentResult, audioResult);
        } else if (matchedVerdict == VerificationVerdict.PARTIAL_MATCH) {
            message = "등록 영상과 일부 프레임이 유사하지만 원본 일치는 확인할 수 없습니다.";
            notice = "영상 길이·순서·구간 차이가 있거나 비교 정보가 부족합니다. 편집 여부를 확정하는 판정은 아닙니다.";
        } else {
            message = "등록 원본과 영상·음성 유사도 기준을 통과했습니다.";
            notice = isSourceSegment(segmentResult)
                    ? String.format("등록 원본의 %d~%dms 대응 구간을 비교했습니다. 영상·음성 지문의 시간 오프셋이 일치합니다. 생략된 맥락이나 모든 변조의 부재를 보증하지 않습니다.",
                    segmentResult.matchedStartMs(), segmentResult.matchedEndMs())
                    : "영상·음성 지문의 유사도와 원본 대응 시간 오프셋을 확인한 결과입니다. 모든 프레임·음성의 무변조를 보증하지 않습니다.";
        }
        log.info("Video verification completed - videoId={}, authentic={}, blockchainVerified={}, vcVerified={}",
                video.getId(), authentic, blockchainVerified, vcVerified);

        return VideoVerifyResponse.of(verdict, similarityDistance, authentic,
                video.getId(), video.getIssuerDid(), video.getRegisteredAt(), registrantName,
                blockchainVerified, vcVerified, vcClaimsBound, true,
                message, notice, segmentResult, audioResult);
    }

    private String buildContentSimilarNotice(SegmentMatchResult seg, SegmentMatchResult audio) {
        if (seg == null) {
            return "영상 구간 비교 정보가 부족해 진본으로 인증하지 않습니다.";
        }
        String videoNotice = String.format(
                "영상 구간 일치율 %.1f%% (%d/%d), 원본 대응 구간 %d~%dms.",
                seg.coverage() * 100, seg.matchedSegments(), seg.totalQuerySegments(),
                seg.matchedStartMs(), seg.matchedEndMs());
        if (audio == null || audio.totalRefSegments() == 0 || audio.totalQuerySegments() == 0) {
            return videoNotice + " 음성 비교 정보가 부족해 진본으로 인증하지 않습니다.";
        }
        String comparisonNotice = videoNotice + String.format(
                " 음성 구간 일치율 %.1f%% (%d/%d).",
                audio.coverage() * 100, audio.matchedSegments(), audio.totalQuerySegments());
        if (audio.coverage() < 1.0 || !audio.orderPreserved()) {
            return comparisonNotice + " 음성 비교 기준을 충족하지 못해 진본으로 인증하지 않습니다.";
        }
        if (seg.bestOffsetMs() != audio.bestOffsetMs()) {
            return comparisonNotice + " 영상과 음성의 원본 대응 시점이 달라 진본으로 인증하지 않습니다.";
        }
        return comparisonNotice + " 영상 비교 기준을 충족하지 못해 진본으로 인증하지 않습니다.";
    }

    private boolean isSourceSegment(SegmentMatchResult seg) {
        return seg != null && seg.totalQuerySegments() > 0
                && seg.totalRefSegments() > seg.totalQuerySegments();
    }

    /**
     * 활성 영상들 중 지각해시가 유사한 영상을 찾고,
     * 세그먼트 지문 + 음성 지문으로 정밀 비교한다.
     *
     * <p>검증 2단계: pHash 후보 선별 → 영상 세그먼트 비교 → 음성 비교 → 종합 판정</p>
     * <ul>
     *   <li>영상 + 음성 모두 통과 → SIMILAR_MATCH (진본)</li>
     *   <li>영상 통과 + 음성 없음 → CONTENT_SIMILAR (보류)</li>
     *   <li>영상 통과 + 음성 불일치 → CONTENT_SIMILAR (차단)</li>
     *   <li>영상 미통과 → 기존 로직</li>
     * </ul>
     */
    private VideoVerifyResponse verifyFingerprint(String inputFingerprint,
                                                   String inputSegment,
                                                   String inputAudio) {
        var match = contentMatchService.find(inputFingerprint, inputSegment, inputAudio);
        if (match.isEmpty()) return VideoVerifyResponse.notRegistered();

        var candidate = match.get();
        Video bestVideo = candidate.video();
        log.info("Content candidate selected - videoId={}, pHashMeanDistance={}",
                bestVideo.getId(), candidate.similarityDistance());

        log.info("Segment comparison - videoId={}, coverage={}, orderPreserved={}, gaps={}",
                bestVideo.getId(),
                candidate.videoMatch() != null ? candidate.videoMatch().coverage() : "N/A",
                candidate.videoMatch() != null ? candidate.videoMatch().orderPreserved() : "N/A",
                candidate.videoMatch() != null ? candidate.videoMatch().unmatchedRanges().size() : "N/A");
        log.info("Audio comparison - videoId={}, coverage={}, silentSegments={}",
                bestVideo.getId(),
                candidate.audioMatch() != null ? candidate.audioMatch().coverage() : "N/A",
                candidate.audioMatch() != null ? candidate.audioMatch().silentSegments() : "N/A");

        return buildVerifyResult(bestVideo, candidate.verdict(), candidate.similarityDistance(),
                candidate.videoMatch(), candidate.audioMatch());
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

    private MediaFingerprintService.Fingerprints generateFingerprints(MultipartFile file) {
        try {
            return mediaFingerprintService.generate(file);
        } catch (IOException e) {
            log.error("Failed to generate media fingerprints - fileName={}", file.getOriginalFilename(), e);
            throw new BusinessException(ErrorCode.VIDEO_PROCESSING_FAILED);
        }
    }

    private MediaFingerprintService.Fingerprints generateFingerprints(Path file) {
        try {
            return mediaFingerprintService.generate(file);
        } catch (IOException e) {
            log.error("Failed to generate media fingerprints from downloaded file", e);
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
