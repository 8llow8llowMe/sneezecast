package com.sneezecast.domainlayer.district.adapter.in.web.exception;

import com.sneezecast.common.dto.Response;
import com.sneezecast.common.exception.ValidationErrorSupport;
import com.sneezecast.domainlayer.district.application.exception.DistrictErrorCode;
import com.sneezecast.domainlayer.district.application.exception.DistrictException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * district 컨텍스트 전용 advice. 범위가 컨텍스트 패키지 전체라 공개 API({@code adapter.in.web})와 내부 API({@code adapter.in.internal})를
 * 함께 덮는다 — 내부 API 의 오류도 Feign 호출자가 읽을 수 있는 {@code Response} 봉투로 나간다.
 *
 * <p>surveillance 에 advice 가 하나뿐이라 {@code @Order} 를 두지 않는다. 컨텍스트 advice 가 늘면 coding-conventions §6-1 대로 순서를 준다.
 */
@RestControllerAdvice(basePackages = "com.sneezecast.domainlayer.district")
public class DistrictExceptionHandler {

    @ExceptionHandler(DistrictException.class)
    public ResponseEntity<Response<Void>> handleDistrictException(DistrictException exception) {
        return ResponseEntity
            .status(exception.getErrorCode().getHttpStatus())
            .body(Response.fail(exception.getErrorCode().getCode(), exception.getMessage()));
    }

    /** 검색 쿼리 파라미터({@code @ModelAttribute} record) 검증 실패. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Response<Void>> handleValidation(MethodArgumentNotValidException exception) {
        return ValidationErrorSupport.toResponse(exception, DistrictErrorCode.INVALID_REQUEST.getCode());
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Response<Void>> handleConstraintViolation(ConstraintViolationException exception) {
        return ValidationErrorSupport.toResponse(exception, DistrictErrorCode.INVALID_REQUEST.getCode());
    }

    /** 경로 변수({@code code}) 형식 위반 — Spring 6.1 내장 메서드 검증이 던진다. */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<Response<Void>> handleHandlerMethodValidation(HandlerMethodValidationException exception) {
        return ValidationErrorSupport.toResponse(exception, DistrictErrorCode.INVALID_REQUEST.getCode());
    }

    /** 지금 district 에는 요청 본문이 없지만, 봉투 없는 Spring 기본 400 이 나갈 틈을 남기지 않는다. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Response<Void>> handleUnreadableBody(HttpMessageNotReadableException exception) {
        return ValidationErrorSupport.toResponse(exception, DistrictErrorCode.INVALID_REQUEST.getCode());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Response<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException exception) {
        return ValidationErrorSupport.toResponse(exception, DistrictErrorCode.PARAMETER_TYPE_INVALID.getCode());
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Response<Void>> handleMissingParameter(MissingServletRequestParameterException exception) {
        DistrictErrorCode errorCode = DistrictErrorCode.PARAMETER_REQUIRED;
        return ResponseEntity
            .status(errorCode.getHttpStatus())
            .body(Response.fail(errorCode.getCode(), errorCode.getMessage() + " (" + exception.getParameterName() + ")"));
    }
}
