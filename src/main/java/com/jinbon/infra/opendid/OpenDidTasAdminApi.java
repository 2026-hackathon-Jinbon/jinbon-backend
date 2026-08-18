package com.jinbon.infra.opendid;

import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

import java.util.Map;

@HttpExchange
public interface OpenDidTasAdminApi {

    @GetExchange("/tas/admin/v1/users/list")
    Map<String, Object> searchUsers(@RequestParam("searchKey") String searchKey,
                                    @RequestParam("searchValue") String searchValue,
                                    @RequestParam("size") int size);
}
