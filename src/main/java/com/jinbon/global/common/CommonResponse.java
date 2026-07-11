package com.jinbon.global.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CommonResponse<T> {

    private final int status;
    private final String code;
    private final String message;
    private final T data;

    public static <T> CommonResponse<T> success(T data) {
        return new CommonResponse<>(200, null, "Success", data);
    }

    public static <T> CommonResponse<T> success(String message, T data) {
        return new CommonResponse<>(200, null, message, data);
    }

    public static CommonResponse<Void> error(String code, int status, String message) {
        return new CommonResponse<>(status, code, message, null);
    }
}
