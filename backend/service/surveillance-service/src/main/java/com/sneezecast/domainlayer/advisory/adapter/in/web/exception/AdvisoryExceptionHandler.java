package com.sneezecast.domainlayer.advisory.adapter.in.web.exception;

import com.sneezecast.common.dto.Response;
import com.sneezecast.common.exception.ValidationErrorSupport;
import com.sneezecast.domainlayer.advisory.application.exception.AdvisoryErrorCode;
import com.sneezecast.domainlayer.advisory.application.exception.AdvisoryException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * advisory 컨텍스트 전용 advice. 범위가 컨텍스트 패키지라 다른 컨텍스트 advice 와 겹치지 않는다. 순서는 다른 컨텍스트 advice 와 같이 0 이다
 * (coding-conventions §6-1). 운영자 API(#246)도 같은 advice 를 쓴다.
 */
@Order(0)
@RestControllerAdvice(basePackages = "com.sneezecast.domainlayer.advisory")
public class AdvisoryExceptionHandler {

    @ExceptionHandler(AdvisoryException.class)
    public ResponseEntity<Response<Void>> handleAdvisoryException(AdvisoryException exception) {
        return ResponseEntity
            .status(exception.getErrorCode().getHttpStatus())
            .body(Response.fail(exception.getErrorCode().getCode(), exception.getMessage()));
    }

    /** 쿼리 파라미터({@code @ModelAttribute} record) · 운영자 요청 본문({@code @RequestBody}) 검증 실패 — 필드별 코드 ADVISORY_101~. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Response<Void>> handleValidation(MethodArgumentNotValidException exception) {
        return ValidationErrorSupport.toResponse(exception, AdvisoryErrorCode.INVALID_REQUEST.getCode());
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Response<Void>> handleConstraintViolation(ConstraintViolationException exception) {
        return ValidationErrorSupport.toResponse(exception, AdvisoryErrorCode.INVALID_REQUEST.getCode());
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<Response<Void>> handleHandlerMethodValidation(HandlerMethodValidationException exception) {
        return ValidationErrorSupport.toResponse(exception, AdvisoryErrorCode.INVALID_REQUEST.getCode());
    }

    /** 운영자 요청 본문이 없거나 JSON 이 아니거나 값의 타입이 틀리면(예: version 이 숫자가 아님) ADVISORY_100 이다 — 봉투 없는 Spring 기본 400 을 남기지 않는다. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Response<Void>> handleUnreadableBody(HttpMessageNotReadableException exception) {
        return ValidationErrorSupport.toResponse(exception, AdvisoryErrorCode.INVALID_REQUEST.getCode());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Response<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException exception) {
        return ValidationErrorSupport.toResponse(exception, AdvisoryErrorCode.PARAMETER_TYPE_INVALID.getCode());
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Response<Void>> handleMissingParameter(MissingServletRequestParameterException exception) {
        AdvisoryErrorCode errorCode = AdvisoryErrorCode.PARAMETER_REQUIRED;
        return ResponseEntity
            .status(errorCode.getHttpStatus())
            .body(Response.fail(errorCode.getCode(), errorCode.getMessage() + " (" + exception.getParameterName() + ")"));
    }
}
