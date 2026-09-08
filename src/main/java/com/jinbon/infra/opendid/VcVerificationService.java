package com.jinbon.infra.opendid;

import com.jinbon.domain.video.port.CredentialVerificationPort;
import com.jinbon.domain.video.port.CredentialVerificationPort.VerificationResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.omnione.did.core.manager.VcManager;
import org.omnione.did.data.model.did.DidDocument;
import org.omnione.did.data.model.vc.Claim;
import org.omnione.did.data.model.vc.VerifiableCredential;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Open DID Issuer 발급 원장에서 VC 상태와 발급 claim을 확인하는 서비스.
 * 실제 VC 서명 검증은 Issuer/Verifier가 제공하는 검증 결과를 신뢰하는 경계다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VcVerificationService implements CredentialVerificationPort {

    private static final String STATUS_ACTIVE = "ACTIVE";
    private final OpenDidIssuerClient issuerClient;
    private final com.jinbon.global.config.OpenDidProperties openDidProperties;

    /**
     * Issuer 발급 원장에 VC가 존재하고 활성 상태인지 확인한다.
     *
     * @param vcId 검증할 VC ID
     * @return 검증 통과 여부
     */
    @Override
    public VerificationResult verify(String vcId) {
        if (!openDidProperties.isEnabled()) {
            log.info("Open DID is disabled, VC cannot be verified - vcId={}", vcId);
            return VerificationResult.disabled();
        }

        log.info("Starting VC verification - vcId={}", vcId);

        try {
            OpenDidIssuerClient.IssuedVc issuedVc = issuerClient.getIssuedVc(vcId);
            String status = issuedVc != null ? issuedVc.status() : null;

            if (!STATUS_ACTIVE.equalsIgnoreCase(status)) {
                log.warn("VC is not active - vcId={}, status={}", vcId, status);
                return VerificationResult.invalid();
            }

            log.info("Issued VC is active - vcId={}", vcId);
            return new VerificationResult(Status.VERIFIED, issuedVc.issuerDid(),
                    issuedVc.subjectDid(), issuedVc.claims());

        } catch (Exception e) {
            log.warn("VC verification failed - vcId={}, reason={}", vcId, e.getMessage());
            return VerificationResult.unavailable();
        }
    }

    @Override
    public VerificationResult verify(String vcId, String credentialJson) {
        if (!openDidProperties.isEnabled()) {
            return VerificationResult.disabled();
        }

        try {
            OpenDidIssuerClient.IssuedVc issuedVc = issuerClient.getIssuedVc(vcId);
            if (issuedVc == null || !STATUS_ACTIVE.equalsIgnoreCase(issuedVc.status())) {
                return VerificationResult.invalid();
            }

            OpenDidIssuerClient.IssuerDocument issuerDocument = issuerClient.getIssuerDocument();
            try {
                VerifiableCredential credential = new VerifiableCredential();
                credential.fromJson(credentialJson);
                if (!vcId.equals(credential.getId())
                        || credential.getIssuer() == null
                        || !issuerDocument.did().equals(credential.getIssuer().getId())
                        || credential.getCredentialSubject() == null
                        || !issuedVc.subjectDid().equals(credential.getCredentialSubject().getId())) {
                    return VerificationResult.invalid();
                }

                DidDocument didDocument = new DidDocument();
                didDocument.fromJson(issuerDocument.json());
                new VcManager().verifyCredential(credential, didDocument, true);

                Map<String, Object> claims = new LinkedHashMap<>();
                if (credential.getCredentialSubject().getClaims() != null) {
                    for (Claim claim : credential.getCredentialSubject().getClaims()) {
                        claims.put(claim.getCode(), claim.getValue());
                    }
                }
                return new VerificationResult(Status.VERIFIED, credential.getIssuer().getId(),
                        credential.getCredentialSubject().getId(), Map.copyOf(claims));
            } catch (Exception e) {
                log.warn("Submitted VC is invalid - vcId={}, reason={}", vcId, e.getMessage());
                return VerificationResult.invalid();
            }
        } catch (Exception e) {
            log.warn("VC verification service is unavailable - vcId={}, reason={}", vcId, e.getMessage());
            return VerificationResult.unavailable();
        }
    }
}
