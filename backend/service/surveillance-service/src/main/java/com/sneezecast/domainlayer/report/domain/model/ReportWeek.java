package com.sneezecast.domainlayer.report.domain.model;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.IsoFields;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 보고 주 — ISO 주(월요일 시작), KST 달력 기준. 저장 값은 {@code YYYY-Www} 8자다 ({@code weekly_report.iso_week}, 예: {@code 2026-W05}).
 *
 * <p><b>주 정의는 고정 계약이다</b> (architecture-guide §9). 연도는 {@link IsoFields#WEEK_BASED_YEAR}, 주는
 * {@link IsoFields#WEEK_OF_WEEK_BASED_YEAR} 로 구한다. {@code DateTimeFormatter} 의 {@code YYYY} · {@code ww} 패턴은 로캘에 따라 주 정의가
 * 바뀌어 12월 29일 ~ 1월 3일에 연도가 어긋나므로 쓰지 않는다. 그래서 2027-01-01(금)은 {@code 2026-W53} 이다.
 *
 * <p>주는 두 자리로 채운다 — 문자열 정렬이 시간 순이 되게 한다 ({@code 2026-W05} &lt; {@code 2026-W10}).
 *
 * @param weekBasedYear ISO 주 기준 연도 (달력 연도와 연말 · 연초에 다를 수 있다)
 * @param week          ISO 주 번호 1 ~ 52 또는 53
 */
public record ReportWeek(
    int weekBasedYear,
    int week
) {

    /** 주 경계를 정하는 시간대. 서버 · JVM 시간대와 무관하게 이 값으로 계산한다. */
    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    private static final Pattern FORMAT = Pattern.compile("(\\d{4})-W(\\d{2})");
    private static final int MIN_YEAR = 1000;
    private static final int MAX_YEAR = 9999;

    public ReportWeek {
        if (weekBasedYear < MIN_YEAR || weekBasedYear > MAX_YEAR) {
            throw new IllegalArgumentException("ISO 주 연도는 네 자리여야 합니다. year=" + weekBasedYear);
        }
        int weeksInYear = weeksIn(weekBasedYear);
        if (week < 1 || week > weeksInYear) {
            throw new IllegalArgumentException("ISO 주 번호가 범위를 벗어났습니다. year=" + weekBasedYear + ", week=" + week + ", max=" + weeksInYear);
        }
    }

    /** 이 순간이 KST 로 속한 주. 요청 시각에서 보고 주를 정할 때 쓴다. */
    public static ReportWeek of(Instant instant) {
        return of(instant.atZone(ZONE).toLocalDate());
    }

    /** KST 달력 날짜가 속한 주. */
    public static ReportWeek of(LocalDate date) {
        return new ReportWeek(date.get(IsoFields.WEEK_BASED_YEAR), date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
    }

    /**
     * 저장 값({@code YYYY-Www})을 되돌린다.
     *
     * @throws IllegalArgumentException 형식이 다르거나 그 해에 없는 주(예: 53주가 없는 해의 {@code W53})인 경우
     */
    public static ReportWeek parse(String value) {
        Matcher matcher = value == null ? null : FORMAT.matcher(value);
        if (matcher == null || !matcher.matches()) {
            throw new IllegalArgumentException("ISO 주 형식(YYYY-Www)이 아닙니다. value=" + value);
        }
        return new ReportWeek(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)));
    }

    /** 저장 · 비교에 쓰는 {@code YYYY-Www} 문자열. */
    public String value() {
        // 로캘마다 숫자 모양이 달라질 수 있어(예: 태국 숫자) 고정 계약인 저장 값은 Locale.ROOT 로 만든다.
        return String.format(Locale.ROOT, "%04d-W%02d", weekBasedYear, week);
    }

    @Override
    public String toString() {
        return value();
    }

    // 12월 28일은 항상 그 해의 마지막 ISO 주에 들어간다.
    private static int weeksIn(int weekBasedYear) {
        return LocalDate.of(weekBasedYear, 12, 28).get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
    }
}
