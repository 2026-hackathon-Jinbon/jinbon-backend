package com.jinbon.infra.opendid;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

import java.util.Map;

@HttpExchange
public interface OpenDidVerifierApi {

    /** VC 상태 조회 (active / revoked / expired) */
    @GetExchange("/api/v1/vc/status")
    Map<String, Object> getVcStatus(@RequestParam("vcId") String vcId);

    /** VC 서명 및 무결성 검증 요청 */
    @PostExchange("/api/v1/verify-vc")
    Map<String, Object> verifyVc(@RequestBody Map<String, Object> body);
}