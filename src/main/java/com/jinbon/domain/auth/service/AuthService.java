package com.jinbon.domain.auth.service;

import com.jinbon.domain.auth.dto.AuthResponse;
import com.jinbon.domain.auth.dto.VerifyRequest;
import com.jinbon.domain.member.entity.Member;
import com.jinbon.domain.member.entity.MemberRole;
import com.jinbon.domain.member.repository.MemberRepository;
import com.jinbon.global.error.BusinessException;
import com.jinbon.infra.omnione.OmniOneCxClient;
import com.jinbon.infra.omnione.dto.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final OmniOneCxClient omniOneCxClient;
    private final MemberRepository memberRepository;
    private final JwtTokenProvider jwtTokenProvider;

    public OacxTokenResponse createOacxToken() {
        return omniOneCxClient.requestToken();
    }

    public OacxAppResponse requestApp(String provider, String token, String txId) {
        return omniOneCxClient.requestWebToApp(provider, token, txId);
    }

    @Transactional
    public AuthResponse verifyAppAndLogin(VerifyRequest request) {
        OacxResultResponse result = omniOneCxClient.verifyApp(
                request.getProvider(), request.getToken(), request.getTxId(), request.getCxId());

        if (!"200".equals(result.getResultCode())) {
            throw new BusinessException("신분증 검증에 실패했습니다.", HttpStatus.UNAUTHORIZED);
        }

        OacxParsedToken parsed = omniOneCxClient.parseToken(result.getToken());
        return processLogin(parsed);
    }

    private AuthResponse processLogin(OacxParsedToken parsed) {
        String ci = parsed.getCi();
        if (ci == null) {
            throw new BusinessException("CI 정보를 가져올 수 없습니다.", HttpStatus.BAD_REQUEST);
        }

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

        String accessToken = jwtTokenProvider.createToken(member.getId(), member.getRole().name());

        return new AuthResponse(accessToken, member.getId(), member.getName(), member.getRole().name());
    }
}
