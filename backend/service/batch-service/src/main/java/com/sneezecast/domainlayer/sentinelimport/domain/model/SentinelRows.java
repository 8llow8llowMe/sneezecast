package com.sneezecast.domainlayer.sentinelimport.domain.model;

import java.math.BigDecimal;

/**
 * 행 record 의 공통 검사. 적재 대상 컬럼 제약(entity-design §3-2)을 원천 경계에서 지킨다 — 넘치는 값을 DB 가 자르거나 반올림하기 전에
 * 응답 해석 실패로 드러낸다. 메시지에는 값을 싣지 않는다.
 *
 * <p>전수신고의 {@code NotifiableRows} 와 같은 규칙이고, 쓰는 컬럼이 달라 도메인마다 따로 둔다 (서비스 · 도메인 간 복제, entity-design §7).
 */
final class SentinelRows {

    /** {@code disease_key} · {@code disease_name} VARCHAR(100). */
    static final int MAX_DISEASE_LENGTH = 100;
    /** {@code disease_group} VARCHAR(20). */
    static final int MAX_DISEASE_GROUP_LENGTH = 20;
    /** {@code period_week} 는 질병관리청 주차 그대로다 (53주가 있는 해가 있다, data-api-analysis §5). */
    static final int MIN_WEEK = 1;
    static final int MAX_WEEK = 53;
    /** {@code metric_value} DECIMAL(12,2) — 정수부 10자리 · 소수부 2자리. */
    private static final int MAX_VALUE_INTEGER_DIGITS = 10;
    private static final int MAX_VALUE_SCALE = 2;

    private SentinelRows() {
    }

    static void requireWeek(int week) {
        if (week < MIN_WEEK || week > MAX_WEEK) {
            throw new IllegalArgumentException("week must be between %d and %d. week=%d".formatted(MIN_WEEK, MAX_WEEK, week));
        }
    }

    static void requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        requireMaxLength(value, field, maxLength);
    }

    /** null 은 통과한다. 길이는 MySQL VARCHAR 처럼 문자(코드 포인트) 수로 센다. */
    static void requireMaxLength(String value, String field, int maxLength) {
        if (value != null && value.codePointCount(0, value.length()) > maxLength) {
            throw new IllegalArgumentException("%s exceeds %d characters. length=%d".formatted(field, maxLength, value.codePointCount(0, value.length())));
        }
    }

    /**
     * null 이거나 DECIMAL(12,2) 에 그대로 들어가는 0 이상의 값이어야 한다. 소수 셋째 자리가 있으면 MySQL 이 조용히 반올림하므로 거절한다.
     * 뒤의 0 은 자리로 치지 않는다 ({@code 1.500} 통과).
     */
    static void requireValue(BigDecimal value) {
        if (value == null) {
            return;
        }
        BigDecimal stripped = value.stripTrailingZeros();
        int integerDigits = stripped.precision() - stripped.scale();
        if (value.signum() < 0 || stripped.scale() > MAX_VALUE_SCALE || integerDigits > MAX_VALUE_INTEGER_DIGITS) {
            throw new IllegalArgumentException("value must be null or between 0 and 9999999999.99 with at most 2 decimal places");
        }
    }
}
