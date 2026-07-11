package com.jinbon.global.error;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public class BusinessException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.status = errorCode.getStatus();
        this.code = errorCode.getCode();
    }
}
