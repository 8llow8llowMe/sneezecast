package com.sneezecast.domainlayer.notifiableimport.application.model;

import com.sneezecast.domainlayer.notifiableimport.application.exception.NotifiableImportErrorCode;
import com.sneezecast.domainlayer.notifiableimport.application.exception.NotifiableImportException;

/**
 * 실행 한 번의 질병관리청 API 호출 상한. 개발계정 일 1,000건 한도를 지키는 장치다 — 페이지가 예상보다 늘거나 재실행이 겹쳐도 한 실행이
 * 한도를 다 쓰지 못하게 한다 (data-api-analysis §2-5, 실행당 약 74회).
 *
 * <p>원천 어댑터가 HTTP 호출 직전마다 {@link #consume()} 한다. 실행 하나 동안만 살고 한 스레드에서만 쓴다 — 스레드 안전하지 않다.
 */
public final class KdcaCallBudget {

    private final int maxCalls;
    private int used;

    public KdcaCallBudget(int maxCalls) {
        if (maxCalls < 1) {
            throw new IllegalArgumentException("maxCalls must be positive. maxCalls=" + maxCalls);
        }
        this.maxCalls = maxCalls;
    }

    /**
     * 호출 한 번을 쓴다. 이미 상한만큼 썼으면 쓰지 않고 {@code REQUEST_BUDGET_EXCEEDED} 다.
     */
    public void consume() {
        if (used >= maxCalls) {
            throw new NotifiableImportException(NotifiableImportErrorCode.REQUEST_BUDGET_EXCEEDED, used, maxCalls);
        }
        used++;
    }

    public int used() {
        return used;
    }

    public int maxCalls() {
        return maxCalls;
    }

    public int remaining() {
        return maxCalls - used;
    }
}
