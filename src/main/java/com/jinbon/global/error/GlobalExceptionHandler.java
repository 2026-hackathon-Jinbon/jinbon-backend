package com.jinbon.global.error;

import com.jinbon.global.common.CommonResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MethodArgumentNotValidException;
import jakarta.validation.ConstraintViolationException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<CommonResponse<Void>> handleMaxUploadSizeExceededException(
            MaxUploadSizeExceededException e) {
        ErrorCode errorCode = ErrorCode.UPLOAD_SIZE_EXCEEDED;
        log.warn("Upload size exceeded: {}", e.getMessage());
        return ResponseEntity
                .status(errorCode.getStatus())
                .body(CommonResponse.error(errorCode.getCode(), errorCode.getStatus().value(), errorCode.getMessage()));
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<CommonResponse<Void>> handleBusinessException(BusinessException e) {
        log.warn("BusinessException: [{}] {}", e.getCode(), e.getMessage());
        return ResponseEntity
                .status(e.getStatus())
                .body(CommonResponse.error(e.getCode(), e.getStatus().value(), e.getMessage()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class})
    public ResponseEntity<CommonResponse<Void>> handleValidationException(Exception e) {
        ErrorCode errorCode = ErrorCode.INVALID_REQUEST;
        log.warn("Invalid request: {}", e.getMessage());
        return ResponseEntity.status(errorCode.getStatus())
                .body(CommonResponse.error(errorCode.getCode(), errorCode.getStatus().value(), errorCode.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<CommonResponse<Void>> handleException(Exception e) {
        log.error("Unexpected error", e);
        ErrorCode errorCode = ErrorCode.INTERNAL_SERVER_ERROR;
        return ResponseEntity
                .status(errorCode.getStatus())
                .body(CommonResponse.error(errorCode.getCode(), errorCode.getStatus().value(), errorCode.getMessage()));
    }
}
