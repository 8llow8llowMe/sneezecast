package com.sneezecast.global.client;

import lombok.Getter;

/**
 * 다른 서비스가 응답하지 못했다 — 5xx · connect / read timeout · 응답 해석 실패 · 서킷 오픈 ({@link InternalClientSupport}).
 *
 * <p>호출 어댑터 안에서만 잡아 자기 도메인의 {@code INTERNAL_SERVICE_UNAVAILABLE}(503) 예외로 바꾼다. 원인(Feign · resilience4j 예외)은
 * cause 에 남겨 로그로 가른다.
 */
@Getter
public class InternalServiceUnavailableException extends RuntimeException {

    private final String targetService;

    public InternalServiceUnavailableException(String targetService, Throwable cause) {
        super("Internal call unavailable target=" + targetService, cause);
        this.targetService = targetService;
    }
}
