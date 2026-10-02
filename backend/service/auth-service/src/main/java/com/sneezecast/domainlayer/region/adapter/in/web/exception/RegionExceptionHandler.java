package com.sneezecast.domainlayer.region.adapter.in.web.exception;

import com.sneezecast.common.dto.Response;
import com.sneezecast.common.exception.ValidationErrorSupport;
import com.sneezecast.domainlayer.region.application.exception.RegionErrorCode;
import com.sneezecast.domainlayer.region.application.exception.RegionException;
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
 * region 컨텍스트 전용 advice. 범위가 auth · member advice 와 겹치지 않아 순서가 결과를 바꾸지 않지만, advice 가 둘 이상이면 순서를 명시한다는
 * 규칙(coding-conventions §6-1)에 따라 {@code AuthExceptionHandler} 와 같은 0 에 둔다 — 회원 예외를 넓게 받는 {@code MemberExceptionHandler}
 * (가장 뒤)보다 앞이다.
 */
@Order(0)
@RestControllerAdvice(basePackages = "com.sneezecast.domainlayer.region")
public class RegionExceptionHandler {

    @ExceptionHandler(RegionException.class)
    public ResponseEntity<Response<Void>> handleRegionException(RegionException exception) {
        return ResponseEntity
            .status(exception.getErrorCode().getHttpStatus())
            .body(Response.fail(exception.getErrorCode().getCode(), exception.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Response<Void>> handleValidation(MethodArgumentNotValidException exception) {
        return ValidationErrorSupport.toResponse(exception, RegionErrorCode.INVALID_REQUEST.getCode());
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Response<Void>> handleConstraintViolation(ConstraintViolationException exception) {
        return ValidationErrorSupport.toResponse(exception, RegionErrorCode.INVALID_REQUEST.getCode());
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<Response<Void>> handleHandlerMethodValidation(HandlerMethodValidationException exception) {
        return ValidationErrorSupport.toResponse(exception, RegionErrorCode.INVALID_REQUEST.getCode());
    }

    /** 깨진 JSON · 타입 불일치 · 빈 본문. 처리하지 않으면 Response 봉투 밖의 Spring 기본 400 이 나간다. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Response<Void>> handleUnreadableBody(HttpMessageNotReadableException exception) {
        return ValidationErrorSupport.toResponse(exception, RegionErrorCode.INVALID_REQUEST.getCode());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Response<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException exception) {
        return ValidationErrorSupport.toResponse(exception, RegionErrorCode.PARAMETER_TYPE_INVALID.getCode());
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Response<Void>> handleMissingParameter(MissingServletRequestParameterException exception) {
        RegionErrorCode errorCode = RegionErrorCode.PARAMETER_REQUIRED;
        return ResponseEntity
            .status(errorCode.getHttpStatus())
            .body(Response.fail(errorCode.getCode(), errorCode.getMessage() + " (" + exception.getParameterName() + ")"));
    }
}
