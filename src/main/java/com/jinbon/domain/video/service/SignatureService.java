package com.jinbon.domain.video.service;

import com.jinbon.global.config.JwtProperties;
import com.jinbon.global.error.BusinessException;
import com.jinbon.global.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/**
 * HMAC-SHA256 기반 전자서명 서비스.
 * JWT Secret을 서명 키로 사용하여 데이터의 무결성을 보장한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SignatureService {

    private final JwtProperties jwtProperties;

    /**
     * 주어진 데이터에 대해 HMAC-SHA256 서명을 생성한다.
     *
     * @param data 서명할 원본 데이터 (예: issuerDid + merkleRoot)
     * @return 16진수 서명 문자열
     */
    public String sign(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec keySpec = new SecretKeySpec(
                    jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(keySpec);
            byte[] result = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            String signature = HexFormat.of().formatHex(result);
            log.debug("HMAC-SHA256 signature generated - dataLength={}, signature={}...",
                    data.length(), signature.substring(0, 16));
            return signature;
        } catch (Exception e) {
            log.error("Signature generation failed - dataLength={}", data.length(), e);
            throw new BusinessException(ErrorCode.SIGNATURE_GENERATION_FAILED);
        }
    }
}
