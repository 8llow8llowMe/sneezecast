package com.sneezecast.domainlayer.member.application.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 회원 도메인 오류. 요청 검증 대역(100~)은 회원 API(#57 이후)가 생길 때 {@code MemberValidationMessage} 와 함께 채운다.
 */
@Getter
@RequiredArgsConstructor
public enum MemberErrorCode {

    // 계정 상태(탈퇴 · 정지)를 드러내지 않도록 상태와 무관하게 같은 응답이다. 이메일 원문은 메시지에 싣지 않는다.
    EXIST_MEMBER_EMAIL("MEMBER_001", "이미 가입된 이메일입니다.", HttpStatus.CONFLICT);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;
}
