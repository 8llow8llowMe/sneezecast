package com.sneezecast.domainlayer.official.domain.model;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;

/**
 * 질병관리청 감시 주차 — <b>일요일 시작, 1월 1일이 들어 있는 주가 1주</b>다 (data-api-analysis §5).
 *
 * <p>ISO 주(월 ~ 일, 1월 4일이 든 주가 1주)와 다르다. 2026년 38주는 09-13 ~ 09-19 이고 ISO 2026-W38 은 09-14 ~ 09-20 이다.
 * 우리 자가보고의 {@code iso_week} 와 섞지 않는다.
 *
 * <p>기간({@code period_start} · {@code period_end}) 계산, 실행일에서 원천 조회 범위를 정하는 일(표본감시 최근 N주), 원천이 준 주차의 검증
 * (53주가 없는 해의 53주 거절)에 쓴다. 자연키(UK)에는 원천이 준 연도 · 주차 정수만 들어가므로, 이 규칙이 바뀌어도 행이 겹치지 않는다.
 *
 * @param year 원천 연도
 * @param week 원천 주차 (1 ~ {@link #weeksInYear(int)})
 */
public record KdcaWeek(int year, int week) {

    public KdcaWeek {
        int weeksInYear = weeksInYear(year);
        if (week < 1 || week > weeksInYear) {
            throw new IllegalArgumentException("KDCA week must be 1..%d. year=%d week=%d".formatted(weeksInYear, year, week));
        }
    }

    /** 그 해의 주 수 = (다음 해 1주 시작 − 그 해 1주 시작) / 7. 52 또는 53 이다 (2022 는 53). */
    public static int weeksInYear(int year) {
        return (int) (ChronoUnit.DAYS.between(firstWeekStart(year), firstWeekStart(year + 1)) / 7);
    }

    /**
     * 그 날짜가 든 주. 연도는 날짜의 연도가 아니라 <b>주가 속한 연도</b>다 — 2025-12-28 은 2026년 1주이고, 2026-01-01 도 2026년 1주다.
     */
    public static KdcaWeek containing(LocalDate date) {
        // 1주 시작은 1월 1일 이전이거나 같으므로 그 해 날짜가 전 해의 주가 되는 일은 없다. 12월 말만 다음 해 1주일 수 있다 (2025-12-28 ~ 12-31).
        int year = date.isBefore(firstWeekStart(date.getYear() + 1)) ? date.getYear() : date.getYear() + 1;
        return new KdcaWeek(year, (int) (ChronoUnit.DAYS.between(firstWeekStart(year), date) / 7) + 1);
    }

    /** {@code weeks} 주 전. 연도 경계와 53주가 있는 해를 넘어간다 (2026년 2주 − 3주 = 2025년 51주). */
    public KdcaWeek minusWeeks(int weeks) {
        return containing(start().minusWeeks(weeks));
    }

    /** 주의 시작(일요일). */
    public LocalDate start() {
        return firstWeekStart(year).plusWeeks(week - 1L);
    }

    /** 주의 끝(토요일) = 시작 + 6일. */
    public LocalDate end() {
        return start().plusDays(6);
    }

    /** 1월 1일이 든 주의 일요일. 1월 1일이 일요일이면 그날이다. */
    private static LocalDate firstWeekStart(int year) {
        return LocalDate.of(year, 1, 1).with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY));
    }
}
