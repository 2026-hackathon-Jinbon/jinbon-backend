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
import com.jinbon.infra.blockchain.ContractEncoder;
import com.jinbon.infra.blockchain.OmniOneChainClient;
import com.jinbon.infra.opendid.VcIssuanceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

        // 동일 영상 중복 확인
        if (videoRepository.existsByFineHash(fineHash)) {
            log.warn("Duplicate video detected - fineHash={}", fineHash);
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

        // VC 발급 시도 (실패해도 등록은 계속 진행)
        String vcId = tryIssueVc(issuerDid);

        // DB 저장
        Video video = Video.builder()
                .title(title)
                .issuerDid(issuerDid)
                .perceptualHash(perceptualHash)
                .fineHash(fineHash)
                .merkleRoot(merkleRoot)
                .merklePath(merklePath)
                .blockNumber(blockNumber)
                .txHash(txHash)
                .signature(signature)
                .version(1)
                .vcId(vcId)
                .build();

        Video saved = videoRepository.save(video);

        log.info("Video registration completed - videoId={}, txHash={}, blockNumber={}, vcId={}, issuerDid={}",
                saved.getId(), txHash, blockNumber, vcId, issuerDid);

        return new VideoRegisterResponse(
                saved.getId(), saved.getTitle(), saved.getMerkleRoot(),
                saved.getTxHash(), saved.getBlockNumber(), saved.getVcId(),
                saved.getRegisteredAt());
    }

    /** 내 영상 목록을 페이징 조회한다 */
    @Transactional(readOnly = true)
    public Page<VideoDetailResponse> getMyVideos(Long memberId, Pageable pageable) {
        Member member = findMemberById(memberId);
        log.debug("Fetching video list - memberId={}, issuerDid={}, page={}, size={}",
                memberId, member.getUserDid(), pageable.getPageNumber(), pageable.getPageSize());
        return videoRepository.findByIssuerDidOrderByRegisteredAtDesc(member.getUserDid(), pageable)
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

    private Member findMemberById(Long memberId) {
        return memberRepository.findById(memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));
    }

    /** 영상 소유권을 확인하고 영상을 반환한다 */
    private Video findOwnedVideo(Long videoId, Long memberId) {
        Member member = findMemberById(memberId);
        Video video = videoRepository.findById(videoId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VIDEO_NOT_FOUND));

        if (!video.getIssuerDid().equals(member.getUserDid())) {
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
        Map<String, Object> receipt = omniOneChainClient.getTransactionReceipt(txHash);
        return receipt != null ? (String) receipt.get("blockNumber") : null;
    }

    /** VC 발급을 시도한다 (실패해도 null 반환, 예외 전파하지 않음) */
    private String tryIssueVc(String issuerDid) {
        try {
            String vcId = vcIssuanceService.issueVideoAuthenticityVc(issuerDid);
            log.info("VC issued successfully - vcId={}, issuerDid={}", vcId, issuerDid);
            return vcId;
        } catch (Exception e) {
            log.warn("VC issuance failed, continuing without VC - issuerDid={}, reason={}", issuerDid, e.getMessage());
            return null;
        }
    }
}
