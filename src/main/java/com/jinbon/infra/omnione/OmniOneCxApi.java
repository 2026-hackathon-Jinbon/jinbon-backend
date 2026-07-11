package com.jinbon.infra.omnione;

import com.jinbon.infra.omnione.dto.OacxAppResponse;
import com.jinbon.infra.omnione.dto.OacxParsedToken;
import com.jinbon.infra.omnione.dto.OacxResultResponse;
import com.jinbon.infra.omnione.dto.OacxTokenResponse;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

import java.util.Map;

@HttpExchange
public interface OmniOneCxApi {

    @PostExchange("/oacx/api/v1.0/trans")
    OacxTokenResponse requestToken();

    @PostExchange("/oacx/api/v1.0/authen/app/request")
    OacxAppResponse requestWebToApp(@RequestBody Map<String, Object> body);

    @PostExchange("/oacx/api/v1.0/authen/app/result")
    OacxResultResponse verifyApp(@RequestBody Map<String, Object> body);

    @PostExchange("/oacx/api/v1.0/trans/token")
    OacxParsedToken parseToken(@RequestBody Map<String, String> body);
}
