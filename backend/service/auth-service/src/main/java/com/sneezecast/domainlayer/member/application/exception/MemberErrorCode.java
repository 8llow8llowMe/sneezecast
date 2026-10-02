package com.sneezecast.domainlayer.member.application.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 회원 도메인 오류. 요청 검증 대역(100~)은 회원 API 가 생길 때 {@code MemberValidationMessage} 와 함께 채운다.
 */
@Getter
@RequiredArgsConstructor
public enum MemberErrorCode {

    // 계정 상태(탈퇴 · 정지)를 드러내지 않도록 상태와 무관하게 같은 응답이다. 이메일 원문은 메시지에 싣지 않는다.
    EXIST_MEMBER_EMAIL("MEMBER_001", "이미 가입된 이메일입니다.", HttpStatus.CONFLICT),
    // 로그인 · 토큰 재발급에서 쓴다. 로그인은 비밀번호가 맞을 때만 이 코드를 낸다 — 틀린 비밀번호로 계정 상태를 떠볼 수 없게 한다.
    WITHDRAWN_MEMBER("MEMBER_002", "탈퇴한 회원입니다.", HttpStatus.FORBIDDEN),
    SUSPENDED_MEMBER("MEMBER_003", "이용이 정지된 회원입니다.", HttpStatus.FORBIDDEN);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;
}
