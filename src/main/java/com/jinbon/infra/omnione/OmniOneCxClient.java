package com.jinbon.infra.omnione;

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
public class OmniOneCxClient {

    private final OmniOneCxApi api;

    /** 인증 세션 토큰을 발급한다 */
    public OacxTokenResponse requestToken() {
        log.info("Requesting OmniOne CX token");
        OacxTokenResponse response = api.requestToken();
        log.info("OmniOne CX token issued - txId={}, resultCode={}", response.getTxId(), response.getResultCode());
        return response;
    }

    /** 모바일 앱 딥링크를 생성한다 (WebToApp 방식) */
    public OacxAppResponse requestWebToApp(String provider, String token, String txId) {
        log.info("Requesting WebToApp deep link - provider={}, txId={}", provider, txId);

        Map<String, Object> body = Map.of(
                "provider", provider + "_v1.5",
                "token", token,
                "txId", txId,
                "contentInfo", Map.of("signType", "ENT_MID")
        );

        OacxAppResponse response = api.requestWebToApp(body);
        log.info("Deep link generated - cxId={}, status={}", response.getCxId(), response.getOacxStatus());
        return response;
    }

    /** 모바일 신분증 검증 결과를 조회한다 */
    public OacxResultResponse verifyApp(String provider, String token, String txId, String cxId) {
        log.info("Verifying app authentication - provider={}, txId={}, cxId={}", provider, txId, cxId);

        Map<String, Object> body = Map.of(
                "provider", provider + "_v1.5",
                "token", token,
                "txId", txId,
                "cxId", cxId
        );

        OacxResultResponse response = api.verifyApp(body);
        log.info("App verification result - resultCode={}, status={}", response.getResultCode(), response.getOacxStatus());
        return response;
    }

    /** 검증 완료된 토큰에서 신원정보(CI, 이름, 생년월일 등)를 추출한다 */
    public OacxParsedToken parseToken(String token) {
        log.debug("Parsing verified token for identity info");
        OacxParsedToken parsed = api.parseToken(Map.of("token", token));
        log.info("Token parsed - name={}, hasCi={}, hasUserDid={}",
                parsed.getName(), parsed.getCi() != null, parsed.getUserDid() != null);
        return parsed;
    }
}
