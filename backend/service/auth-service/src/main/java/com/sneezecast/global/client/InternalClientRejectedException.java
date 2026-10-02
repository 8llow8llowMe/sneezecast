package com.sneezecast.global.client;

import lombok.Getter;

/**
 * 다른 서비스가 요청을 4xx 로 거절했다 ({@link InternalClientSupport}). 장애가 아니라 서킷에 집계하지 않는다.
 *
 * <p>호출 어댑터 안에서만 잡는다 — 상태 · 결과 코드로 뜻을 정해(예: 404 + 리소스 없음 → empty) 자기 도메인 예외나 반환값으로 바꾼다.
 * 어댑터 밖으로 흘리면 봉투 없는 500 이 된다.
 */
@Getter
public class InternalClientRejectedException extends RuntimeException {

    private final String targetService;
    private final int status;
    /** 상대 오류 봉투의 {@code dataHeader.resultCode}. 봉투가 아닌 응답(경로가 없는 기본 404 등)이면 null */
    private final String resultCode;

    public InternalClientRejectedException(String targetService, int status, String resultCode, Throwable cause) {
        super("Internal call rejected target=" + targetService + " status=" + status + " resultCode=" + resultCode, cause);
        this.targetService = targetService;
        this.status = status;
        this.resultCode = resultCode;
    }

    public boolean isStatus(int expected) {
        return status == expected;
    }
}
