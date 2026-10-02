package com.sneezecast.domainlayer.member.adapter.in.web.exception;

import com.sneezecast.common.dto.Response;
import com.sneezecast.domainlayer.member.application.exception.MemberException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 회원 도메인 예외를 {@code Response} 봉투로 바꾼다.
 *
 * <p>범위를 auth · member 컨텍스트로 둔다 — 가입 · 로그인은 auth 컨텍스트 컨트롤러가 받지만 회원 불변식(이메일 unique 등) 위반은
 * {@link MemberException} 으로 올라온다. 회원 예외를 쓰지 않는 다른 컨텍스트(consent · region 등)까지 넓히지 않는다. 검증 예외처럼 컨텍스트마다 코드가 다른 예외는 다루지 않고 각 컨텍스트 advice 에 맡긴다.
 * (member 컨트롤러의 검증 오류는 {@link MemberRequestExceptionHandler}). 그래서 가장 뒤({@link Ordered#LOWEST_PRECEDENCE})에 둔다.
 */
@Order(Ordered.LOWEST_PRECEDENCE)
@RestControllerAdvice(basePackages = {"com.sneezecast.domainlayer.auth", "com.sneezecast.domainlayer.member"})
public class MemberExceptionHandler {

    @ExceptionHandler(MemberException.class)
    public ResponseEntity<Response<Void>> handleMemberException(MemberException exception) {
        return ResponseEntity
            .status(exception.getErrorCode().getHttpStatus())
            .body(Response.fail(exception.getErrorCode().getCode(), exception.getMessage()));
    }
}
