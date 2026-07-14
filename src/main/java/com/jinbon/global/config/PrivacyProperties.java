package com.jinbon.global.config;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 개인정보 보호 관련 설정.
 *
 * CI(연계정보) HMAC 해싱에 사용하는 비밀키를 관리한다.
 * JWT 시크릿과 반드시 다른 독립적인 키를 사용해야 한다.
 * (키 재사용 시 하나의 유출로 두 보안 도메인이 동시 침해됨)
 */
@Getter
@RequiredArgsConstructor
@ConfigurationProperties(prefix = "privacy")
@Validated
public class PrivacyProperties {

    /** CI HMAC-SHA256 비밀키 (32자 이상, JWT_SECRET과 별도 관리 필수) */
    @NotBlank(message = "CI_HMAC_SECRET must be configured independently from JWT_SECRET")
    private final String ciHmacSecret;
}
