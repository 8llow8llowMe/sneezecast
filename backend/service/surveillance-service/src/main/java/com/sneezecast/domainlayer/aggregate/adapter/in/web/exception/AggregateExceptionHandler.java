package com.sneezecast.domainlayer.aggregate.adapter.in.web.exception;

import com.sneezecast.common.dto.Response;
import com.sneezecast.common.exception.ValidationErrorSupport;
import com.sneezecast.domainlayer.aggregate.application.exception.AggregateErrorCode;
import com.sneezecast.domainlayer.aggregate.application.exception.AggregateException;
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
 * aggregate 컨텍스트 전용 advice. 범위가 컨텍스트 패키지라 district · report advice 와 겹치지 않는다 — 경로가 {@code /api/v1/districts/**} 여도
 * 집계 컨트롤러의 오류는 AGGREGATE 코드로 나간다. 순서는 다른 컨텍스트 advice 와 같이 0 이다 (coding-conventions §6-1).
 */
@Order(0)
@RestControllerAdvice(basePackages = "com.sneezecast.domainlayer.aggregate")
public class AggregateExceptionHandler {

    @ExceptionHandler(AggregateException.class)
    public ResponseEntity<Response<Void>> handleAggregateException(AggregateException exception) {
        return ResponseEntity
            .status(exception.getErrorCode().getHttpStatus())
            .body(Response.fail(exception.getErrorCode().getCode(), exception.getMessage()));
    }

    /** 지도 쿼리 파라미터({@code @ModelAttribute} record) 검증 실패 — 필드별 코드 AGGREGATE_101~. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Response<Void>> handleValidation(MethodArgumentNotValidException exception) {
        return ValidationErrorSupport.toResponse(exception, AggregateErrorCode.INVALID_REQUEST.getCode());
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Response<Void>> handleConstraintViolation(ConstraintViolationException exception) {
        return ValidationErrorSupport.toResponse(exception, AggregateErrorCode.INVALID_REQUEST.getCode());
    }

    /** 경로 변수({@code code}) · 쿼리({@code week}) 형식 위반 — Spring 6.1 내장 메서드 검증이 던진다. */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<Response<Void>> handleHandlerMethodValidation(HandlerMethodValidationException exception) {
        return ValidationErrorSupport.toResponse(exception, AggregateErrorCode.INVALID_REQUEST.getCode());
    }

    /** 지금 집계 API 에는 요청 본문이 없지만, 봉투 없는 Spring 기본 400 이 나갈 틈을 남기지 않는다. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Response<Void>> handleUnreadableBody(HttpMessageNotReadableException exception) {
        return ValidationErrorSupport.toResponse(exception, AggregateErrorCode.INVALID_REQUEST.getCode());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Response<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException exception) {
        return ValidationErrorSupport.toResponse(exception, AggregateErrorCode.PARAMETER_TYPE_INVALID.getCode());
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Response<Void>> handleMissingParameter(MissingServletRequestParameterException exception) {
        AggregateErrorCode errorCode = AggregateErrorCode.PARAMETER_REQUIRED;
        return ResponseEntity
            .status(errorCode.getHttpStatus())
            .body(Response.fail(errorCode.getCode(), errorCode.getMessage() + " (" + exception.getParameterName() + ")"));
    }
}
