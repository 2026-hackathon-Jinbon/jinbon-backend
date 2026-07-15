package com.jinbon.domain.auth.service;

import com.jinbon.domain.auth.dto.AuthResponse;
import com.jinbon.domain.auth.dto.VerifyRequest;
import com.jinbon.domain.auth.dto.SignupIdentityResponse;
import com.jinbon.domain.member.entity.Member;
import com.jinbon.domain.member.entity.MemberRole;
import com.jinbon.domain.member.entity.MemberStatus;
import com.jinbon.domain.member.repository.MemberRepository;
import com.jinbon.domain.video.repository.VideoRepository;
import com.jinbon.global.error.BusinessException;
import com.jinbon.global.error.ErrorCode;
import com.jinbon.infra.omnione.OmniOneCxClient;
import com.jinbon.infra.omnione.dto.OacxParsedToken;
import com.jinbon.infra.omnione.dto.OacxResultResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 인증 서비스.
 *
 * OmniOne CX 모바일 신분증 검증 후 JWT 토큰을 발급한다.
 * CI(연계정보)는 HMAC-SHA256 해시로 변환하여 회원을 조회한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final OmniOneCxClient omniOneCxClient;
    private final MemberRepository memberRepository;
    private final VideoRepository videoRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenService refreshTokenService;
    private final DidRebindTokenService didRebindTokenService;
    private final CiHasher ciHasher;

    /** OmniOne CX 인증 세션 토큰을 발급한다 */
    public com.jinbon.infra.omnione.dto.OacxTokenResponse createOacxToken() {
        return omniOneCxClient.requestToken();
    }

    /** WebToApp 딥링크를 생성한다 */
    public com.jinbon.infra.omnione.dto.OacxAppResponse requestApp(String provider, String token, String txId) {
        return omniOneCxClient.requestWebToApp(provider, token, txId);
    }

    /**
     * 모바일 신분증 검증 후 로그인 처리한다.
     * 검증 성공 시 신원정보를 추출하고, CI 기반으로 회원 조회 후 JWT를 발급한다.
     */
    @Transactional
    public AuthResponse verifyAppAndLogin(VerifyRequest request) {
        log.info("App verification started - provider={}, txId={}", request.provider(), request.txId());

        OacxResultResponse result = omniOneCxClient.verifyApp(
                request.provider(), request.token(), request.txId(), request.cxId());

        ensureVerificationCompleted(result);

        OacxParsedToken parsed = omniOneCxClient.parseToken(result.getToken());
        return processLogin(parsed);
    }

    /** Refresh Token으로 새 토큰 쌍을 발급한다 (Refresh Token Rotation) */
    public AuthResponse refresh(String refreshToken) {
        if (!jwtTokenProvider.validateToken(refreshToken)) {
            log.warn("Invalid refresh token received");
            throw new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN);
        }

        if (!"refresh".equals(jwtTokenProvider.getTokenType(refreshToken))) {
            throw new BusinessException(ErrorCode.NOT_A_REFRESH_TOKEN);
        }

        Long memberId = jwtTokenProvider.getMemberId(refreshToken);

        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));

        ensureActive(member);

        String role = member.getRole().name();
        String newAccessToken = jwtTokenProvider.createAccessToken(member.getId(), role);
        String newRefreshToken = jwtTokenProvider.createRefreshToken(member.getId(), role);

        if (!refreshTokenService.rotate(memberId, refreshToken, newRefreshToken)) {
            log.warn("Refresh token expired or already used - memberId={}", memberId);
            throw new BusinessException(ErrorCode.REFRESH_TOKEN_EXPIRED);
        }
        log.info("Token refreshed - memberId={}, role={}", memberId, role);

        return authResponse(member, newAccessToken, newRefreshToken, null);
    }

    /** Refresh Token을 무효화하여 로그아웃 처리한다 */
    public void logout(String refreshToken) {
        if (jwtTokenProvider.validateToken(refreshToken)) {
            Long memberId = jwtTokenProvider.getMemberId(refreshToken);
            refreshTokenService.delete(memberId);
            log.info("Logout completed - memberId={}", memberId);
        }
    }

    /**
     * 신원정보로 로그인을 처리한다.
     * CI 해시로 가입 완료된 기존 회원만 조회한다.
     */
    private AuthResponse processLogin(OacxParsedToken parsed) {
        String verifiedCi = parsed.getCi();
        if (verifiedCi == null) {
            log.error("CI not found in parsed token");
            throw new BusinessException(ErrorCode.CI_NOT_FOUND);
        }

        String ciHash = ciHasher.hash(verifiedCi);
        Member member = findByCiHash(ciHash)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));

        ensureActive(member);

        String role = member.getRole().name();
        String accessToken = jwtTokenProvider.createAccessToken(member.getId(), role);
        String refreshToken = jwtTokenProvider.createRefreshToken(member.getId(), role);

        refreshTokenService.save(member.getId(), refreshToken);

        log.info("Login successful - memberId={}, role={}", member.getId(), role);

        String didRebindToken = jwtTokenProvider.createDidRebindToken(member.getId());
        didRebindTokenService.save(member.getId(), didRebindToken);
        return authResponse(member, accessToken, refreshToken, didRebindToken);
    }

    /**
     * 회원가입용 본인확인을 처리한다.
     * CI를 HMAC 해싱하여 기존 회원을 조회하고, 미가입자면 PENDING 상태로 생성한다.
     */
    @Transactional
    public SignupIdentityResponse verifyAppForSignup(VerifyRequest request) {
        OacxResultResponse result = omniOneCxClient.verifyApp(
                request.provider(), request.token(), request.txId(), request.cxId());
        ensureVerificationCompleted(result);
        OacxParsedToken parsed = omniOneCxClient.parseToken(result.getToken());
        if (parsed.getCi() == null) {
            throw new BusinessException(ErrorCode.CI_NOT_FOUND);
        }

        String ciHash = ciHasher.hash(parsed.getCi());
        Member member = findByCiHash(ciHash).orElseGet(() -> memberRepository.save(
                Member.create(ciHash, null, parsed.getName(), parsed.getBirth(),
                        MemberRole.USER, MemberStatus.PENDING)
        ));
        if (member.getStatus() == MemberStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.MEMBER_ALREADY_REGISTERED);
        }
        return new SignupIdentityResponse(jwtTokenProvider.createSignupToken(member.getId()),
                member.getId(), member.getName(), member.getStatus().name());
    }

    /**
     * HMAC 해시로 회원을 찾는다.
     *
     * @param ciHash HMAC-SHA256 해시 ("h1:" 접두사 포함)
     * @return 회원 Optional
     */
    private Optional<Member> findByCiHash(String ciHash) {
        return memberRepository.findByCiHash(ciHash);
    }

    @Transactional
    public AuthResponse completeSignup(String signupToken, String did) {
        if (!jwtTokenProvider.validateToken(signupToken)
                || !"signup".equals(jwtTokenProvider.getTokenType(signupToken))) {
            throw new BusinessException(ErrorCode.NOT_A_SIGNUP_TOKEN);
        }
        Member member = memberRepository.findById(jwtTokenProvider.getMemberId(signupToken))
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));
        if (member.getStatus() == MemberStatus.ACTIVE) {
            if (!did.equals(member.getUserDid())) {
                throw new BusinessException(ErrorCode.MEMBER_ALREADY_REGISTERED);
            }
        } else {
            memberRepository.findByUserDid(did)
                    .filter(owner -> !owner.getId().equals(member.getId()))
                    .ifPresent(owner -> { throw new BusinessException(ErrorCode.DID_ALREADY_REGISTERED); });
            member.updateDid(did);
        }

        String role = member.getRole().name();
        String accessToken = jwtTokenProvider.createAccessToken(member.getId(), role);
        String refreshToken = jwtTokenProvider.createRefreshToken(member.getId(), role);
        refreshTokenService.save(member.getId(), refreshToken);
        return authResponse(member, accessToken, refreshToken, null);
    }

    /**
     * 앱 재설치 후 Wallet DID를 재바인딩한다.
     *
     * TOCTOU 방어: 토큰을 먼저 소비(1회용 처리)한 뒤 DID 중복 체크를 수행한다.
     * 토큰 소비 → DID 중복 확인 순서로 처리하여, 동시 요청에 의한 중복 바인딩을 방지한다.
     * DB의 userDid unique 제약도 최종 안전장치로 기능한다.
     */
    @Transactional
    public AuthResponse rebindDid(String didRebindToken, String did) {
        if (!jwtTokenProvider.validateToken(didRebindToken)
                || !"did_rebind".equals(jwtTokenProvider.getTokenType(didRebindToken))) {
            throw new BusinessException(ErrorCode.NOT_A_DID_REBIND_TOKEN);
        }

        Long memberId = jwtTokenProvider.getMemberId(didRebindToken);
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));
        ensureActive(member);

        // 토큰을 먼저 소비하여 동시 요청에 의한 TOCTOU 공격을 차단한다
        if (!didRebindTokenService.consume(memberId, didRebindToken)) {
            throw new BusinessException(ErrorCode.NOT_A_DID_REBIND_TOKEN);
        }

        // 토큰 소비 후 DID 중복 확인 (DB unique 제약이 최종 안전장치)
        memberRepository.findByUserDid(did)
                .filter(owner -> !owner.getId().equals(member.getId()))
                .ifPresent(owner -> { throw new BusinessException(ErrorCode.DID_ALREADY_REGISTERED); });

        String previousDid = member.getUserDid();
        if (previousDid != null) {
            videoRepository.claimLegacyVideos(member.getId(), previousDid);
        }
        member.rebindDid(did);
        String role = member.getRole().name();
        String accessToken = jwtTokenProvider.createAccessToken(member.getId(), role);
        String refreshToken = jwtTokenProvider.createRefreshToken(member.getId(), role);
        refreshTokenService.save(member.getId(), refreshToken);
        log.info("DID reconnected - memberId={}, did={}", member.getId(), did);
        return authResponse(member, accessToken, refreshToken, null);
    }

    /** 회원 상태가 ACTIVE인지 검증하고, 레거시 USER 역할을 ISSUER로 자동 승격한다 */
    private void ensureActive(Member member) {
        if (member.getStatus() == MemberStatus.PENDING) {
            throw new BusinessException(ErrorCode.SIGNUP_NOT_COMPLETED);
        }
        if (member.getStatus() != MemberStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.MEMBER_NOT_ACTIVE);
        }
        // 가입 완료 회원은 모두 ISSUER — 레거시 USER 역할 자동 보정
        if (member.getRole() != MemberRole.ISSUER) {
            member.promoteToIssuer();
            log.info("Legacy member role promoted to ISSUER - memberId={}", member.getId());
        }
    }

    /** OmniOne CX 검증 결과 코드를 확인한다 */
    private void ensureVerificationCompleted(OacxResultResponse result) {
        String resultCode = result.getResultCode();
        if ("200".equals(resultCode)) {
            return;
        }
        if ("402".equals(resultCode) || "408".equals(resultCode) || "30020".equals(resultCode)) {
            throw new BusinessException(ErrorCode.ID_VERIFICATION_PENDING);
        }
        log.warn("ID verification failed - resultCode={}, oacxCode={}, message={}",
                resultCode, result.getOacxCode(), result.getClientMessage());
        throw new BusinessException(ErrorCode.ID_VERIFICATION_FAILED);
    }

    private AuthResponse authResponse(Member member, String accessToken, String refreshToken,
                                      String didRebindToken) {
        return new AuthResponse(accessToken, refreshToken, member.getId(), member.getName(),
                member.getRole().name(), member.getStatus().name(), member.getUserDid(), didRebindToken);
    }
}
