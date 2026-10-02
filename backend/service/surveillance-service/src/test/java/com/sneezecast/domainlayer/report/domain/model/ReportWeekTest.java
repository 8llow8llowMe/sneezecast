package com.sneezecast.domainlayer.report.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 주 정의(ISO 주, 월요일 시작, KST)는 고정 계약이다 (architecture-guide §9). 주 경계 · 연말 연초 · 자릿수를 날짜로 못 박는다.
 */
class ReportWeekTest {

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
        // 연말: 2026 은 1월 1일이 목요일이라 53주까지 있다. 2027-01-01(금) ~ 01-03(일)은 아직 2026 의 마지막 주다.
        "2026-12-31, 2026-W53",
        "2027-01-01, 2026-W53",
        "2027-01-03, 2026-W53",
        "2027-01-04, 2027-W01",
        // 연초: 2025-12-29(월)은 이미 2026 의 첫 주다 — 달력 연도와 주 연도가 다르다.
        "2025-12-29, 2026-W01",
        // 주는 두 자리로 채운다.
        "2026-01-30, 2026-W05"
    })
    @DisplayName("날짜가 속한 ISO 주 — 연도는 주 기준 연도, 주는 두 자리")
    void weekOfDate(LocalDate date, String expected) {
        assertThat(ReportWeek.of(date).value()).isEqualTo(expected);
    }

    @Test
    @DisplayName("일요일 23:59:59 KST 는 그 주, 월요일 00:00 KST 는 다음 주다")
    void weekBoundaryIsMondayMidnightKst() {
        assertThat(ReportWeek.of(Instant.parse("2026-10-04T14:59:59Z")).value()).isEqualTo("2026-W40");
        assertThat(ReportWeek.of(Instant.parse("2026-10-04T15:00:00Z")).value()).isEqualTo("2026-W41");
    }

    @Test
    @DisplayName("UTC 로는 일요일이어도 KST 로 월요일이면 다음 주다 — 서버 시간대와 무관하다")
    void usesKstNotUtc() {
        // 2026-10-04T20:00Z = 일요일(UTC) = 2026-10-05 05:00 월요일(KST)
        assertThat(ReportWeek.of(Instant.parse("2026-10-04T20:00:00Z")).value()).isEqualTo("2026-W41");
    }

    @Test
    @ResourceLock("java.util.Locale")
    @DisplayName("기본 로캘이 일요일 시작(미국)이어도 주가 바뀌지 않는다 — YYYY · ww 패턴을 쓰지 않는다")
    void independentOfDefaultLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.US);
            assertThat(ReportWeek.of(LocalDate.parse("2027-01-03")).value()).isEqualTo("2026-W53");
            assertThat(ReportWeek.of(LocalDate.parse("2026-10-04")).value()).isEqualTo("2026-W40");
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    @ResourceLock("java.util.Locale")
    @DisplayName("기본 로캘이 ASCII 가 아닌 숫자(태국 숫자)를 써도 저장 값은 ASCII 숫자다 — CHAR(8) · 정규식 계약")
    void asciiDigitsUnderNonLatinNumberingLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("th-TH-u-nu-thai"));
            String value = ReportWeek.of(LocalDate.parse("2026-02-02")).value();
            assertThat(value).isEqualTo("2026-W06");
            assertThat(ReportWeek.parse(value).value()).isEqualTo("2026-W06");
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    @DisplayName("저장 값을 되돌리면 같은 주다 — 53주가 있는 해의 W53 도 받는다")
    void parseRoundTrips() {
        assertThat(ReportWeek.parse("2026-W05")).isEqualTo(new ReportWeek(2026, 5));
        assertThat(ReportWeek.parse("2026-W53").value()).isEqualTo("2026-W53");
        assertThat(ReportWeek.parse("2027-W01")).isEqualTo(ReportWeek.of(LocalDate.parse("2027-01-04")));
        assertThat(ReportWeek.parse("2026-W40").toString()).isEqualTo("2026-W40");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "2026-W5", "2026W05", "26-W05", "2026-w05", "2026-W00", "2027-W53", "2026-W54", " 2026-W05"})
    @DisplayName("형식이 다르거나 그 해에 없는 주는 거부한다 — 2027 은 52주까지다")
    void parseRejectsInvalid(String value) {
        assertThatThrownBy(() -> ReportWeek.parse(value)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("문자열 정렬이 시간 순이다")
    void stringOrderIsChronological() {
        assertThat(ReportWeek.parse("2026-W05").value()).isLessThan(ReportWeek.parse("2026-W10").value());
        assertThat(ReportWeek.parse("2026-W53").value()).isLessThan(ReportWeek.parse("2027-W01").value());
    }
}
