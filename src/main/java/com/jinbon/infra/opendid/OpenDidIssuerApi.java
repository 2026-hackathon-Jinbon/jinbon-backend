package com.jinbon.infra.opendid;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

import java.util.Map;

@HttpExchange
public interface OpenDidIssuerApi {

    @PostExchange("/api/v1/request-offer")
    Map<String, Object> requestOffer(@RequestBody Map<String, Object> body);

    @PostExchange("/api/v1/inspect-propose-issue")
    Map<String, Object> inspectProposeIssue(@RequestBody Map<String, Object> body);

    @PostExchange("/api/v1/generate-issue-profile")
    void generateIssueProfile(@RequestBody Map<String, Object> body);

    @PostExchange("/api/v1/issue-vc")
    Map<String, Object> issueVc(@RequestBody Map<String, Object> body);

    @PostExchange("/api/v1/complete-vc")
    Map<String, Object> completeVc(@RequestBody Map<String, Object> body);

    @GetExchange("/api/v1/issue-vc/result")
    Map<String, Object> getIssueVcResult(@RequestParam("txId") String txId);
}
