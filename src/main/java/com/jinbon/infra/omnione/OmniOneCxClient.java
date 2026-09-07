package com.jinbon.infra.omnione;

import com.jinbon.domain.auth.port.IdentityVerificationPort;
import com.jinbon.global.error.BusinessException;
import com.jinbon.global.error.ErrorCode;
import com.jinbon.infra.omnione.dto.OacxAppResponse;
import com.jinbon.infra.omnione.dto.OacxParsedToken;
import com.jinbon.infra.omnione.dto.OacxResultResponse;
import com.jinbon.infra.omnione.dto.OacxTokenResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * OmniOne CX API 클라이언트 (필수과제 - 모바일 신분증 연동).
 *
 * 인증 흐름: 토큰 생성 → WebToApp 딥링크 요청 → 검증 결과 조회 → 토큰 파싱(신원정보 추출)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OmniOneCxClient implements IdentityVerificationPort {

    private final OmniOneCxApi api;

    /** 인증 세션 토큰을 발급한다 */
    public VerificationSession createSession() {
        log.info("Requesting OmniOne CX token");
        OacxTokenResponse response = api.requestToken();
        log.info("OmniOne CX token issued - txId={}, resultCode={}", response.getTxId(), response.getResultCode());
        return new VerificationSession(
                response.getToken(), response.getTxId(), response.getOacxCode(), response.getResultCode());
    }

    /** 모바일 앱 딥링크를 생성한다 (WebToApp 방식) */
    public AppRequest requestApp(String provider, String token, String txId) {
        log.info("Requesting WebToApp deep link - provider={}, txId={}", provider, txId);

        Map<String, Object> body = Map.of(
                "provider", provider + "_v1.5",
                "token", token,
                "txId", txId,
                "contentInfo", Map.of("signType", "ENT_MID")
        );

        OacxAppResponse response = api.requestWebToApp(body);
        log.info("Deep link generated - cxId={}, status={}", response.getCxId(), response.getOacxStatus());
        return new AppRequest(response.getToken(), response.getCxId(), response.getData(),
                response.getOacxStatus(), response.getOacxCode(), response.getResultCode(),
                response.getReqTxId(), response.getClientMessage(), response.getProvider());
    }

    /** 모바일 신분증 검증 결과를 조회한다 */
    public VerifiedIdentity verify(String provider, String token, String txId, String cxId) {
        log.info("Verifying app authentication - provider={}, txId={}, cxId={}", provider, txId, cxId);

        Map<String, Object> body = Map.of(
                "provider", provider + "_v1.5",
                "token", token,
                "txId", txId,
                "cxId", cxId
        );

        OacxResultResponse response = api.verifyApp(body);
        log.info("App verification result - resultCode={}, status={}", response.getResultCode(), response.getOacxStatus());
        ensureVerificationCompleted(response);

        log.debug("Parsing verified token for identity info");
        OacxParsedToken parsed = api.parseToken(Map.of("token", response.getToken()));
        log.info("Token parsed - name={}, hasCi={}, hasUserDid={}",
                parsed.getName(), parsed.getCi() != null, parsed.getUserDid() != null);
        return new VerifiedIdentity(parsed.getCi(), parsed.getName(), parsed.getBirth());
    }

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
}
