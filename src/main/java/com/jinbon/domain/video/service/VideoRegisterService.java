package com.jinbon.domain.video.service;

import com.jinbon.domain.member.entity.Member;
import com.jinbon.domain.member.entity.MemberRole;
import com.jinbon.domain.member.repository.MemberRepository;
import com.jinbon.domain.video.dto.VideoDetailResponse;
import com.jinbon.domain.video.dto.VideoRegisterResponse;
import com.jinbon.domain.video.entity.Video;
import com.jinbon.domain.video.repository.VideoRepository;
import com.jinbon.global.error.BusinessException;
import com.jinbon.global.error.ErrorCode;
import com.jinbon.global.config.OpenDidProperties;
import com.jinbon.infra.blockchain.ContractEncoder;
import com.jinbon.infra.blockchain.OmniOneChainClient;
import com.jinbon.infra.opendid.VcIssuanceService;
import com.jinbon.infra.opendid.VcIssuanceService.VcIssuancePreparation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

/**
 * 영상 등록/조회/비활성화 서비스.
 *
 * 영상 등록 흐름:
 * 1. 회원 조회 + ISSUER 권한 검증
 * 2. 영상 해시 생성 (perceptualHash + fineHash)
 * 3. 중복 영상 확인
 * 4. 머클트리 생성 + 전자서명
 * 5. 블록체인 기록 (OmniOne Chain)
 * 6. VC 발급 시도 (Open DID)
 * 7. DB 저장
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VideoRegisterService {

    private final MemberRepository memberRepository;
    private final VideoRepository videoRepository;
    private final HashService hashService;
    private final PerceptualHashService perceptualHashService;
    private final SignatureService signatureService;
    private final OmniOneChainClient omniOneChainClient;
    private final VcIssuanceService vcIssuanceService;
    private final com.jinbon.infra.opendid.VcVerificationService vcVerificationService;
    private final OpenDidProperties openDidProperties;
    private final VideoVerifyService videoVerifyService;
    private final RedisTemplate<String, String> redisTemplate;

    /**
     * 영상을 등록한다.
     * 해시 생성 → 블록체인 기록 → VC 발급 → DB 저장 순서로 처리한다.
     */
    @Transactional
    public VideoRegisterResponse register(MultipartFile file, String title, Long memberId) {
        Member member = findMemberById(memberId);
        validateIssuer(member);

        String issuerDid = member.getUserDid();
        log.info("Video registration started - memberId={}, issuerDid={}, title={}, fileSize={}bytes",
                memberId, issuerDid, title, file.getSize());

        // fineHash 생성 (SHA-256, 전체 파일)
        String fineHash = generateFineHash(file);
        log.debug("Fine hash generated - fineHash={}", fineHash.substring(0, 16) + "...");

        // 완전히 동일한 파일은 회원에 따라 구분한다.
        // 같은 회원의 재시도는 기존 결과를 반환하여 블록체인 중복 기록을 방지한다.
        // 다른 회원의 동일 파일 등록은 소유권을 판정하지 않고 권한 확인 대상으로 차단한다.
        var existingVideo = videoRepository.findByFineHash(fineHash);
        if (existingVideo.isPresent()) {
            Video existing = existingVideo.get();
            boolean sameMember = memberId.equals(existing.getMemberId())
                    || (existing.getMemberId() == null && issuerDid.equals(existing.getIssuerDid()));
            if (sameMember) {
                log.info("Idempotent video registration - memberId={}, existingVideoId={}", memberId, existing.getId());
                VcIssuancePreparation preparation = existing.getVcId() == null
                        ? prepareVcIssuance(existing) : null;
                return toRegisterResponse(existing, true, preparation);
            }
            log.warn("Duplicate video owned by another member - requesterMemberId={}, existingVideoId={}",
                    memberId, existing.getId());
            throw new BusinessException(ErrorCode.VIDEO_ALREADY_REGISTERED);
        }

        // 지각해시 생성 (DCT 기반 pHash, 프레임별)
        String perceptualHash = generatePerceptualHash(file);
        log.debug("Perceptual hash generated - fingerprint={}...",
                perceptualHash.substring(0, Math.min(32, perceptualHash.length())));

        // 머클트리 구성 + 전자서명
        String merkleRoot = hashService.buildMerkleRoot(perceptualHash, fineHash);
        String merklePath = hashService.buildMerklePath(perceptualHash, fineHash, merkleRoot);
        String signature = signatureService.sign(issuerDid + merkleRoot);
        log.debug("Merkle tree built - merkleRoot={}, signature generated", merkleRoot.substring(0, 16) + "...");

        // 블록체인 기록
        String txHash = sendBlockchainTx(ContractEncoder.encodeRegister(merkleRoot, issuerDid, signature));
        String blockNumber = fetchBlockNumber(txHash);
        log.info("Blockchain recorded - txHash={}, blockNumber={}", txHash, blockNumber);

        // DB 저장 (fineHash unique 제약 위반 시 동시 요청에 의한 중복으로 처리)
        Video video = Video.create(title, issuerDid, memberId, perceptualHash, fineHash,
                merkleRoot, merklePath, blockNumber, txHash, signature, 1);

        Video saved;
        try {
            saved = videoRepository.save(video);
        } catch (DataIntegrityViolationException e) {
            log.warn("Concurrent duplicate registration detected - fineHash={}", fineHash.substring(0, 16) + "...");
            throw new BusinessException(ErrorCode.VIDEO_ALREADY_REGISTERED);
        }
        VcIssuancePreparation preparation = prepareVcIssuance(saved);

        log.info("Video registration completed - videoId={}, txHash={}, blockNumber={}, vcId={}, issuerDid={}",
                saved.getId(), txHash, blockNumber, saved.getVcId(), issuerDid);

        return toRegisterResponse(saved, false, preparation);
    }

    /** 내 영상 목록을 페이징 조회한다 */
    @Transactional
    public Page<VideoDetailResponse> getMyVideos(Long memberId, Pageable pageable) {
        Member member = findMemberById(memberId);
        log.debug("Fetching video list - memberId={}, issuerDid={}, page={}, size={}",
                memberId, member.getUserDid(), pageable.getPageNumber(), pageable.getPageSize());
        videoRepository.claimLegacyVideos(memberId, member.getUserDid());
        return videoRepository.findByMemberIdOrderByRegisteredAtDesc(memberId, pageable)
                .map(VideoDetailResponse::from);
    }

    /** 영상 상세 정보를 조회한다 (소유권 확인 포함) */
    @Transactional(readOnly = true)
    public VideoDetailResponse getVideoDetail(Long videoId, Long memberId) {
        Video video = findOwnedVideo(videoId, memberId);
        log.debug("Video detail retrieved - videoId={}, memberId={}", videoId, memberId);
        return VideoDetailResponse.from(video);
    }

    /**
     * 영상을 비활성화한다.
     * 블록체인에 비활성화 기록 → DB 상태 변경 → 검증 캐시 제거
     */
    @Transactional
    public void deactivate(Long videoId, Long memberId) {
        Video video = findOwnedVideo(videoId, memberId);

        if (!video.isActive()) {
            throw new BusinessException(ErrorCode.VIDEO_ALREADY_DEACTIVATED);
        }

        log.info("Video deactivation started - videoId={}, merkleRoot={}", videoId, video.getMerkleRoot());

        // 블록체인에 비활성화 기록
        sendBlockchainTx(ContractEncoder.encodeDeactivate(video.getMerkleRoot(), video.getIssuerDid()));

        // DB 비활성화 + 검증 캐시 제거
        video.deactivate();
        videoVerifyService.evictCache(video.getFineHash());

        log.info("Video deactivation completed - videoId={}", videoId);
    }

    @Transactional
    public void completeVcIssuance(Long videoId, Long memberId, String vcId) {
        Video video = findOwnedVideo(videoId, memberId);
        if (!vcVerificationService.verify(vcId)) {
            throw new BusinessException(ErrorCode.VC_VERIFICATION_FAILED);
        }
        video.completeVcIssuance(vcId);
        log.info("Wallet VC issuance confirmed - videoId={}, memberId={}, vcId={}", videoId, memberId, vcId);
    }

    private Member findMemberById(Long memberId) {
        return memberRepository.findById(memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));
    }

    /** 영상 소유권을 확인하고 영상을 반환한다 */
    private Video findOwnedVideo(Long videoId, Long memberId) {
        Member member = findMemberById(memberId);
        Video video = videoRepository.findById(videoId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VIDEO_NOT_FOUND));

        boolean legacyOwned = video.getMemberId() == null
                && video.getIssuerDid().equals(member.getUserDid());
        if (legacyOwned) {
            videoRepository.claimLegacyVideos(memberId, member.getUserDid());
        }
        if (!memberId.equals(video.getMemberId()) && !legacyOwned) {
            log.warn("Video ownership mismatch - videoId={}, videoIssuerDid={}, requestDid={}",
                    videoId, video.getIssuerDid(), member.getUserDid());
            throw new BusinessException(ErrorCode.VIDEO_NOT_OWNED);
        }
        return video;
    }

    /** ISSUER 역할 + DID 등록 여부를 검증한다 */
    private void validateIssuer(Member member) {
        if (member.getRole() != MemberRole.ISSUER) {
            throw new BusinessException(ErrorCode.ISSUER_ROLE_REQUIRED);
        }
        if (member.getUserDid() == null) {
            throw new BusinessException(ErrorCode.ISSUER_DID_NOT_REGISTERED);
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

    /** 블록체인 트랜잭션을 전송하고 txHash를 반환한다 (실패 시 예외) */
    private String sendBlockchainTx(String data) {
        String txHash = omniOneChainClient.sendTransaction(data);
        if (txHash == null) {
            log.error("Blockchain transaction returned null");
            throw new BusinessException(ErrorCode.BLOCKCHAIN_TX_FAILED);
        }
        return txHash;
    }

    /** 트랜잭션 영수증에서 블록 번호를 추출한다 */
    private String fetchBlockNumber(String txHash) {
        for (int attempt = 0; attempt < 20; attempt++) {
            Map<String, Object> receipt = omniOneChainClient.getTransactionReceipt(txHash);
            if (receipt != null) {
                if (!"0x1".equals(receipt.get("status"))) {
                    throw new BusinessException(ErrorCode.BLOCKCHAIN_TX_FAILED);
                }
                Object blockNumber = receipt.get("blockNumber");
                if (blockNumber == null) {
                    throw new BusinessException(ErrorCode.BLOCKCHAIN_TX_FAILED);
                }
                return blockNumber.toString();
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BusinessException(ErrorCode.BLOCKCHAIN_TX_FAILED);
            }
        }
        throw new BusinessException(ErrorCode.BLOCKCHAIN_TX_FAILED);
    }

    private VcIssuancePreparation prepareVcIssuance(Video video) {
        try {
            VcIssuancePreparation preparation = vcIssuanceService.prepareVideoVc(
                    video.getIssuerDid(),
                    Map.of(
                            openDidProperties.claimKey("videoHash"), video.getFineHash(),
                            openDidProperties.claimKey("uploaderDid"), video.getIssuerDid(),
                            openDidProperties.claimKey("uploadTimestamp"), video.getRegisteredAt().toString(),
                            openDidProperties.claimKey("videoTitle"), video.getTitle()
                    )
            );
            if (preparation != null) {
                video.markVcPending();
            }
            return preparation;
        } catch (Exception e) {
            log.warn("VC preparation failed, video remains registered - videoId={}, reason={}",
                    video.getId(), e.getMessage());
            return null;
        }
    }

    private VideoRegisterResponse toRegisterResponse(
            Video video,
            boolean alreadyRegistered,
            VcIssuancePreparation preparation
    ) {
        return VideoRegisterResponse.from(
                video, alreadyRegistered,
                preparation != null ? preparation.vcPlanId() : null,
                preparation != null ? preparation.issuerDid() : null,
                preparation != null ? preparation.offerId() : null
        );
    }
}
