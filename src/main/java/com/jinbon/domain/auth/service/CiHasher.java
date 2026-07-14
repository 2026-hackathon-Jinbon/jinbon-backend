package com.jinbon.domain.auth.service;

import com.jinbon.global.config.PrivacyProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

/**
 * CI(연계정보) HMAC-SHA256 해싱 컴포넌트.
 *
 * CI 원문을 비가역적으로 해싱하여 DB에 저장 가능한 식별자로 변환한다.
 * 해시 결과에 버전 접두사("h1:")를 붙여 향후 알고리즘 교체 시 구분할 수 있도록 한다.
 *
 * 보안 요구사항:
 * - CI_HMAC_SECRET은 32자 이상의 독립 비밀키 (JWT_SECRET과 별도 관리)
 * - 기동 시 키 길이를 검증하여 약한 키 사용을 차단
 */
@Component
@RequiredArgsConstructor
public class CiHasher {

    private static final String ALGORITHM = "HmacSHA256";
    private static final String VERSION_PREFIX = "h1:";
    private final PrivacyProperties properties;

    /** SecretKeySpec을 캐싱하여 매 호출 시 재생성을 방지한다 */
    private SecretKeySpec cachedKeySpec;

    /** 기동 시 비밀키 길이를 검증하고 SecretKeySpec을 캐싱한다 */
    @PostConstruct
    void init() {
        String secret = properties.getCiHmacSecret();
        if (secret == null || secret.length() < 32) {
            throw new IllegalStateException("CI_HMAC_SECRET must be at least 32 characters");
        }
        cachedKeySpec = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM);
    }

    /**
     * CI 원문을 HMAC-SHA256으로 해싱한다.
     *
     * @param ci CI 원문 (본인확인 결과에서 추출)
     * @return "h1:" + HMAC-SHA256 hex (예: "h1:a1b2c3...")
     */
    public String hash(String ci) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(cachedKeySpec);
            return VERSION_PREFIX + HexFormat.of().formatHex(
                    mac.doFinal(ci.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to protect CI", e);
        }
    }
}
