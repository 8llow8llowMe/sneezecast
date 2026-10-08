package com.sneezecast.domainlayer.sentinelimport.application.model;

import com.sneezecast.domainlayer.sentinelimport.application.exception.SentinelImportErrorCode;
import com.sneezecast.domainlayer.sentinelimport.application.exception.SentinelImportException;

/**
 * 실행 한 번의 감염병포털 호출 상한. 공개 API 가 아닌 화면 데이터를 부르므로 원천에 부담을 주지 않으려는 장치다 — 요청마다 화면 + 데이터 두 번을
 * 부르고, 계획이 늘거나 재실행이 겹쳐도 한 실행이 정해진 수를 넘지 못하게 한다 (data-api-analysis §3-5).
 *
 * <p>원천 어댑터가 HTTP 호출 직전마다 {@link #consume()} 한다. 실행 하나 동안만 살고 한 스레드에서만 쓴다 — 스레드 안전하지 않다.
 */
public final class SentinelCallBudget {

    private final int maxCalls;
    private int used;

    public SentinelCallBudget(int maxCalls) {
        if (maxCalls < 1) {
            throw new IllegalArgumentException("maxCalls must be positive. maxCalls=" + maxCalls);
        }
        this.maxCalls = maxCalls;
    }

    /** 호출 한 번을 쓴다. 이미 상한만큼 썼으면 쓰지 않고 {@code REQUEST_BUDGET_EXCEEDED} 다. */
    public void consume() {
        if (used >= maxCalls) {
            throw new SentinelImportException(SentinelImportErrorCode.REQUEST_BUDGET_EXCEEDED, used, maxCalls);
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
