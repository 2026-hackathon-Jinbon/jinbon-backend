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

    @GetExchange("/issuer/admin/v1/issuer/info")
    Map<String, Object> getIssuerInfo();

    @PostExchange("/issuer/admin/v1/users/demo")
    void registerHolder(@RequestBody Map<String, Object> body);

    /**
     * 등록된 홀더 목록을 조회한다.
     *
     * Issuer 2.0.0의 searchKey는 vcSchemaId/title만 지원하고 그 외 값(did, pii)은
     * predicate가 FALSE로 접혀 항상 빈 결과가 나오므로 검색 파라미터를 쓰지 않는다.
     */
    @GetExchange("/issuer/admin/v1/users")
    Map<String, Object> listHolders(@RequestParam("size") int size);

    @PatchExchange("/issuer/admin/v1/users")
    void updateHolder(@RequestBody Map<String, Object> body);

}
