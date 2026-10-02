package com.sneezecast.domainlayer.report.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 보고 주는 서버가 Clock 으로 정한다. 고정 Clock 으로 주 경계를 재현한다 (경계 날짜 표는 {@code ReportWeekTest}).
 */
class ReportWeekCalculatorTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Test
    @DisplayName("일요일 23:59:59 KST 는 그 주, 월요일 00:00 KST 는 다음 주다")
    void currentWeekAroundMondayMidnight() {
        assertThat(calculatorAt("2026-10-04T14:59:59Z", KST).currentWeek().value()).isEqualTo("2026-W40");
        assertThat(calculatorAt("2026-10-04T15:00:00Z", KST).currentWeek().value()).isEqualTo("2026-W41");
    }

    @Test
    @DisplayName("Clock 의 zone 이 UTC 여도 KST 로 계산한다 — UTC 일요일 · KST 월요일이면 다음 주")
    void ignoresClockZone() {
        assertThat(calculatorAt("2026-10-04T20:00:00Z", ZoneOffset.UTC).currentWeek().value()).isEqualTo("2026-W41");
    }

    @Test
    @DisplayName("연말 연초는 주 기준 연도를 따른다")
    void yearBoundary() {
        assertThat(calculatorAt("2027-01-01T03:00:00Z", KST).currentWeek().value()).isEqualTo("2026-W53");
        assertThat(calculatorAt("2027-01-03T15:00:00Z", KST).currentWeek().value()).isEqualTo("2027-W01");
    }

    private static ReportWeekCalculator calculatorAt(String instant, ZoneId zone) {
        return new ReportWeekCalculator(Clock.fixed(Instant.parse(instant), zone));
    }
}
