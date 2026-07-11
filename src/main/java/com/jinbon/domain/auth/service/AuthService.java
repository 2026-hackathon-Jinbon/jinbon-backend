package com.jinbon.domain.auth.service;

import com.jinbon.domain.auth.dto.AuthResponse;
import com.jinbon.domain.auth.dto.VerifyRequest;
import com.jinbon.domain.member.entity.Member;
import com.jinbon.domain.member.entity.MemberRole;
import com.jinbon.domain.member.repository.MemberRepository;
import com.jinbon.global.error.BusinessException;
import com.jinbon.global.error.ErrorCode;
import com.jinbon.infra.omnione.OmniOneCxClient;
import com.jinbon.infra.omnione.dto.OacxParsedToken;
import com.jinbon.infra.omnione.dto.OacxResultResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 인증 서비스.
 *
 * OmniOne CX 모바일 신분증 검증 후 JWT 토큰을 발급한다.
 * CI(연계정보) 기반으로 회원을 자동 생성하거나 기존 회원을 조회한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final OmniOneCxClient omniOneCxClient;
    private final MemberRepository memberRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenService refreshTokenService;

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
     * 검증 성공 시 신원정보를 추출하고, CI 기반으로 회원 조회/생성 후 JWT를 발급한다.
     */
    @Transactional
    public AuthResponse verifyAppAndLogin(VerifyRequest request) {
        log.info("App verification started - provider={}, txId={}", request.provider(), request.txId());

        OacxResultResponse result = omniOneCxClient.verifyApp(
                request.provider(), request.token(), request.txId(), request.cxId());

        if (!"200".equals(result.getResultCode())) {
            log.warn("ID verification failed - resultCode={}, txId={}", result.getResultCode(), request.txId());
            throw new BusinessException(ErrorCode.ID_VERIFICATION_FAILED);
        }

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

        if (!refreshTokenService.validate(memberId, refreshToken)) {
            log.warn("Refresh token expired or already used - memberId={}", memberId);
            throw new BusinessException(ErrorCode.REFRESH_TOKEN_EXPIRED);
        }

        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));

        String role = member.getRole().name();
        String newAccessToken = jwtTokenProvider.createAccessToken(member.getId(), role);
        String newRefreshToken = jwtTokenProvider.createRefreshToken(member.getId(), role);

        refreshTokenService.save(member.getId(), newRefreshToken);
        log.info("Token refreshed - memberId={}, role={}", memberId, role);

        return new AuthResponse(newAccessToken, newRefreshToken, member.getId(), member.getName(), role);
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
     * CI로 기존 회원을 조회하고, 없으면 신규 회원을 자동 생성한다.
     */
    private AuthResponse processLogin(OacxParsedToken parsed) {
        String ci = parsed.getCi();
        if (ci == null) {
            log.error("CI not found in parsed token - name={}", parsed.getName());
            throw new BusinessException(ErrorCode.CI_NOT_FOUND);
        }

        boolean isNewMember = !memberRepository.findByCi(ci).isPresent();
        Member member = memberRepository.findByCi(ci)
                .orElseGet(() -> memberRepository.save(
                        Member.builder()
                                .ci(ci)
                                .userDid(parsed.getUserDid())
                                .name(parsed.getName())
                                .birth(parsed.getBirth())
                                .role(MemberRole.USER)
                                .build()
                ));

        String role = member.getRole().name();
        String accessToken = jwtTokenProvider.createAccessToken(member.getId(), role);
        String refreshToken = jwtTokenProvider.createRefreshToken(member.getId(), role);

        refreshTokenService.save(member.getId(), refreshToken);

        log.info("Login successful - memberId={}, name={}, role={}, isNewMember={}",
                member.getId(), member.getName(), role, isNewMember);

        return new AuthResponse(accessToken, refreshToken, member.getId(), member.getName(), role);
    }
}
