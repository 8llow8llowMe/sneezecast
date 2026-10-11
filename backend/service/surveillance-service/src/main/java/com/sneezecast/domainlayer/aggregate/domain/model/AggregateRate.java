package com.sneezecast.domainlayer.aggregate.domain.model;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 공개 응답의 비율 표현 (entity-design §2-2 — 비율은 저장하지 않고 조회 때 센 수로 계산한다). <b>퍼센트, 소수 첫째 자리, 반올림(HALF_UP)</b>이다 —
 * 20 / 120 = 16.666…% → {@code 16.7}, 1 / 8 = 12.5% → {@code 12.5}, 1 / 16 = 6.25% → {@code 6.3}.
 *
 * <p>화면 표시용이다. 단계 · 변화 판정은 반올림한 값이 아니라 센 수끼리의 정수 비교로 한다 ({@link AggregateRule}) — 반올림한 두 값의 차로 판정하면
 * 경계(정확히 3%p)가 어긋날 수 있다.
 */
public final class AggregateRate {

    /** 소수 자리 수. */
    public static final int SCALE = 1;

    private static final BigDecimal PERCENT = BigDecimal.valueOf(100);

    private AggregateRate() {
    }

    /**
     * {@code count / whole} 을 퍼센트로.
     *
     * @throws IllegalArgumentException 분모가 0 이하이거나 분자가 음수 · 분모보다 큰 경우 — 집계 행 불변식이 깨진 것이다
     */
    public static BigDecimal percent(long count, long whole) {
        if (whole <= 0 || count < 0 || count > whole) {
            throw new IllegalArgumentException("비율을 계산할 수 없습니다. count=" + count + ", whole=" + whole);
        }
        return BigDecimal.valueOf(count).multiply(PERCENT).divide(BigDecimal.valueOf(whole), SCALE, RoundingMode.HALF_UP);
    }
}
