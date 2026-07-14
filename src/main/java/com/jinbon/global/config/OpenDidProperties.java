package com.jinbon.global.config;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Open DID 설정.
 *
 * VC 발급(Issuer) 및 검증(Verifier) 서버 연동에 필요한 속성을 관리한다.
 * enabled=false로 설정하면 VC 발급/검증을 건너뛴다 (Open DID 미구동 환경용).
 */
@Getter
@RequiredArgsConstructor
@ConfigurationProperties(prefix = "opendid")
public class OpenDidProperties {

    /** VC 발급/검증 활성화 여부 */
    private final boolean enabled;

    /** Issuer 서버 URL */
    private final String issuerServerUrl;

    /** Verifier 서버 URL */
    private final String verifierServerUrl;

    /** VC Plan ID (발급 정책 식별자) */
    private final String vcPlanId;

    /** VC Claim 네임스페이스 (예: ns-jinbon-video-01) */
    private final String vcClaimNamespace;

    /** Issuer DID (VC 발급자 식별자) */
    private final String issuerDid;

    /**
     * 네임스페이스와 claimId를 결합하여 정규화된 Claim 키를 생성한다.
     * 끝에 불필요한 점(.)이 있으면 제거한다.
     *
     * @param claimId Claim 식별자 (예: "videoHash", "uploaderDid")
     * @return 정규화된 키 (예: "ns-jinbon-video-01.videoHash")
     */
    public String claimKey(String claimId) {
        String namespace = vcClaimNamespace.endsWith(".")
                ? vcClaimNamespace.substring(0, vcClaimNamespace.length() - 1)
                : vcClaimNamespace;
        return namespace + "." + claimId;
    }
}
