package com.jinbon.infra.opendid;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;
import org.springframework.web.service.annotation.PatchExchange;

import java.util.Map;

@HttpExchange
public interface OpenDidIssuerApi {

    @PostExchange("/issuer/api/v1/request-offer")
    Map<String, Object> requestOffer(@RequestBody Map<String, Object> body);

    @PostExchange("/issuer/api/v1/inspect-propose-issue")
    Map<String, Object> inspectProposeIssue(@RequestBody Map<String, Object> body);

    @PostExchange("/issuer/api/v1/generate-issue-profile")
    void generateIssueProfile(@RequestBody Map<String, Object> body);

    @PostExchange("/issuer/api/v1/issue-vc")
    Map<String, Object> issueVc(@RequestBody Map<String, Object> body);

    @PostExchange("/issuer/api/v1/complete-vc")
    Map<String, Object> completeVc(@RequestBody Map<String, Object> body);

    @GetExchange("/issuer/api/v1/issue-vc/result")
    Map<String, Object> getIssueVcResult(@RequestParam("txId") String txId);

    @GetExchange("/issuer/admin/v1/issue-profiles")
    Map<String, Object> searchIssueProfiles(@RequestParam("searchKey") String searchKey,
                                            @RequestParam("searchValue") String searchValue,
                                            @RequestParam("size") int size);

    @GetExchange("/issuer/admin/v1/issue-profiles")
    Map<String, Object> listIssueProfiles(@RequestParam("size") int size);

    @PostExchange("/issuer/admin/v1/users/demo")
    void registerHolder(@RequestBody Map<String, Object> body);

    @GetExchange("/issuer/admin/v1/users")
    Map<String, Object> searchHolders(@RequestParam("searchKey") String searchKey,
                                      @RequestParam("searchValue") String searchValue,
                                      @RequestParam("size") int size);

    @PatchExchange("/issuer/admin/v1/users")
    void updateHolder(@RequestBody Map<String, Object> body);

    @GetExchange("/issuer/admin/v1/issuer/info")
    Map<String, Object> getIssuerInfo();
}
