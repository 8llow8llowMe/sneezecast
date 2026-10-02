package com.sneezecast.domainlayer.member.adapter.in.web.exception;

import com.sneezecast.common.dto.Response;
import com.sneezecast.common.exception.ValidationErrorSupport;
import com.sneezecast.domainlayer.member.application.exception.MemberErrorCode;
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
 * member 컨텍스트 컨트롤러의 요청 검증 · 역직렬화 오류를 MEMBER 대역(100 · 1xx · 198 · 199) 봉투로 바꾼다.
 *
 * <p>{@link MemberExceptionHandler} 와 나눈 이유 — 그쪽은 auth 컨텍스트 컨트롤러에서 올라온 회원 예외도 받도록 범위가 auth · member 둘이다. 검증
 * 예외까지 거기 두면 auth 컨트롤러의 검증 오류가 auth advice 에 빠진 유형일 때 MEMBER 코드로 새어 나간다. 검증은 컨텍스트마다 코드가 다르므로 범위를
 * member 하나로 좁힌 advice 를 따로 둔다 ({@code AuthExceptionHandler} 와 같은 모양).
 */
@Order(0)
@RestControllerAdvice(basePackages = "com.sneezecast.domainlayer.member")
public class MemberRequestExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Response<Void>> handleValidation(MethodArgumentNotValidException exception) {
        return ValidationErrorSupport.toResponse(exception, MemberErrorCode.INVALID_REQUEST.getCode());
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Response<Void>> handleConstraintViolation(ConstraintViolationException exception) {
        return ValidationErrorSupport.toResponse(exception, MemberErrorCode.INVALID_REQUEST.getCode());
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<Response<Void>> handleHandlerMethodValidation(HandlerMethodValidationException exception) {
        return ValidationErrorSupport.toResponse(exception, MemberErrorCode.INVALID_REQUEST.getCode());
    }

    /** 깨진 JSON · 타입 불일치. 처리하지 않으면 Response 봉투 밖의 Spring 기본 400 이 나간다. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Response<Void>> handleUnreadableBody(HttpMessageNotReadableException exception) {
        return ValidationErrorSupport.toResponse(exception, MemberErrorCode.INVALID_REQUEST.getCode());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Response<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException exception) {
        return ValidationErrorSupport.toResponse(exception, MemberErrorCode.PARAMETER_TYPE_INVALID.getCode());
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Response<Void>> handleMissingParameter(MissingServletRequestParameterException exception) {
        MemberErrorCode errorCode = MemberErrorCode.PARAMETER_REQUIRED;
        return ResponseEntity
            .status(errorCode.getHttpStatus())
            .body(Response.fail(errorCode.getCode(), errorCode.getMessage() + " (" + exception.getParameterName() + ")"));
    }
}
