package com.sneezecast.security.common.exception;

import lombok.Getter;

@Getter
public class SecurityJwtException extends RuntimeException {

    private final SecurityErrorCode errorCode;

    public SecurityJwtException(SecurityErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    /** 원인(jjwt 예외 등)을 함께 남긴다 — 응답에는 나가지 않고, 로그에서 어느 검사에 걸렸는지 볼 때 쓴다. */
    public SecurityJwtException(SecurityErrorCode errorCode, Throwable cause) {
        super(errorCode.getMessage(), cause);
        this.errorCode = errorCode;
    }
}
