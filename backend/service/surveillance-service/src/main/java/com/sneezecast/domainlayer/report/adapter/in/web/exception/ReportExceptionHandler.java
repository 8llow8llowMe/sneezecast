package com.sneezecast.domainlayer.report.adapter.in.web.exception;

import com.sneezecast.common.dto.Response;
import com.sneezecast.common.exception.ValidationErrorSupport;
import com.sneezecast.domainlayer.report.application.exception.ReportErrorCode;
import com.sneezecast.domainlayer.report.application.exception.ReportException;
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
 * report 컨텍스트 전용 advice. 범위가 컨텍스트 패키지라 district advice 와 겹치지 않는다 — 순서는 auth 의 컨텍스트별 advice 와 같이 0 으로 맞춘다
 * (coding-conventions §6-1). 공개 보고 API 와 내부 파기 API({@code adapter/in/internal})가 함께 쓴다.
 *
 * <p>인가 실패(401 · 403)는 여기서 받지 않는다. {@code @PreAuthorize} 거부는 보안 필터의 오류 writer 가 {@code SECURITY_00x} 봉투로 쓴다.
 */
@Order(0)
@RestControllerAdvice(basePackages = "com.sneezecast.domainlayer.report")
public class ReportExceptionHandler {

    @ExceptionHandler(ReportException.class)
    public ResponseEntity<Response<Void>> handleReportException(ReportException exception) {
        return ResponseEntity
            .status(exception.getErrorCode().getHttpStatus())
            .body(Response.fail(exception.getErrorCode().getCode(), exception.getMessage()));
    }

    /** 제출 본문({@code WeeklyReportRequest}) 검증 실패 — 필드별 코드 REPORT_101~. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Response<Void>> handleValidation(MethodArgumentNotValidException exception) {
        return ValidationErrorSupport.toResponse(exception, ReportErrorCode.INVALID_REQUEST.getCode());
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Response<Void>> handleConstraintViolation(ConstraintViolationException exception) {
        return ValidationErrorSupport.toResponse(exception, ReportErrorCode.INVALID_REQUEST.getCode());
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<Response<Void>> handleHandlerMethodValidation(HandlerMethodValidationException exception) {
        return ValidationErrorSupport.toResponse(exception, ReportErrorCode.INVALID_REQUEST.getCode());
    }

    /** 깨진 JSON · 본문 없음 · 알 수 없는 증상군 값 — REPORT_100 이고 어느 필드인지(허용 값 포함)는 밝힌다. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Response<Void>> handleUnreadableBody(HttpMessageNotReadableException exception) {
        return ValidationErrorSupport.toResponse(exception, ReportErrorCode.INVALID_REQUEST.getCode());
    }

    /** 내부 파기 API 의 회원 ID 가 숫자가 아니거나 long 범위를 넘으면 REPORT_198 — 봉투 없는 Spring 기본 400 이 나갈 틈을 남기지 않는다. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Response<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException exception) {
        return ValidationErrorSupport.toResponse(exception, ReportErrorCode.PARAMETER_TYPE_INVALID.getCode());
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Response<Void>> handleMissingParameter(MissingServletRequestParameterException exception) {
        ReportErrorCode errorCode = ReportErrorCode.PARAMETER_REQUIRED;
        return ResponseEntity
            .status(errorCode.getHttpStatus())
            .body(Response.fail(errorCode.getCode(), errorCode.getMessage() + " (" + exception.getParameterName() + ")"));
    }
}
