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

    @GetExchange("/issuer/admin/v1/issue-profiles")
    Map<String, Object> listIssueProfiles(@RequestParam("size") int size);

    @GetExchange("/issuer/admin/v1/issued-vcs")
    Map<String, Object> searchIssuedVcs(@RequestParam("searchKey") String searchKey,
                                        @RequestParam("searchValue") String searchValue,
                                        @RequestParam("size") int size);

    @PostExchange("/issuer/admin/v1/users/demo")
    void registerHolder(@RequestBody Map<String, Object> body);

    @GetExchange("/issuer/admin/v1/users")
    Map<String, Object> searchHolders(@RequestParam("searchKey") String searchKey,
                                      @RequestParam("searchValue") String searchValue,
                                      @RequestParam("size") int size);

    @PatchExchange("/issuer/admin/v1/users")
    void updateHolder(@RequestBody Map<String, Object> body);

}
