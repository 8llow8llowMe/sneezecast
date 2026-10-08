package com.sneezecast.domainlayer.official.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 예시 값은 data-api-analysis §5 (표본감시 · 전수신고 데이터의 연도 경계로 확인한 값)다. */
class KdcaWeekTest {

    @Test
    @DisplayName("2025년 1주 = 2024-12-29(일) ~ 2025-01-04(토) — 1월 1일이 든 주가 1주다")
    void firstWeekOf2025StartsInPreviousYear() {
        KdcaWeek week = new KdcaWeek(2025, 1);

        assertThat(week.start()).isEqualTo(LocalDate.of(2024, 12, 29));
        assertThat(week.end()).isEqualTo(LocalDate.of(2025, 1, 4));
    }

    @Test
    @DisplayName("2026년 1주 = 2025-12-28 ~ 2026-01-03")
    void firstWeekOf2026() {
        KdcaWeek week = new KdcaWeek(2026, 1);

        assertThat(week.start()).isEqualTo(LocalDate.of(2025, 12, 28));
        assertThat(week.end()).isEqualTo(LocalDate.of(2026, 1, 3));
    }

    @Test
    @DisplayName("2026년 38주 = 2026-09-13 ~ 09-19 — ISO 2026-W38(09-14 ~ 09-20)과 하루 어긋난다")
    void week38Of2026() {
        KdcaWeek week = new KdcaWeek(2026, 38);

        assertThat(week.start()).isEqualTo(LocalDate.of(2026, 9, 13));
        assertThat(week.end()).isEqualTo(LocalDate.of(2026, 9, 19));
    }

    @Test
    @DisplayName("1월 1일이 일요일인 해(2023)는 1주가 1월 1일에 시작한다")
    void yearStartingOnSunday() {
        assertThat(new KdcaWeek(2023, 1).start()).isEqualTo(LocalDate.of(2023, 1, 1));
    }

    @Test
    @DisplayName("주 수: 2020 = 52, 2022 = 53, 2025 = 52 (MMWR 방식이면 53 · 52 · 53 이라 틀린다)")
    void weeksInYear() {
        assertThat(KdcaWeek.weeksInYear(2020)).isEqualTo(52);
        assertThat(KdcaWeek.weeksInYear(2022)).isEqualTo(53);
        assertThat(KdcaWeek.weeksInYear(2025)).isEqualTo(52);
    }

    @Test
    @DisplayName("2022년 53주는 받고, 그 다음 주는 2023년 1주다")
    void lastWeekOf2022IsFollowedBy2023FirstWeek() {
        KdcaWeek week53 = new KdcaWeek(2022, 53);

        assertThat(week53.end().plusDays(1)).isEqualTo(new KdcaWeek(2023, 1).start());
    }

    @Test
    @DisplayName("2025년 53주 · 0주는 없다")
    void rejectsWeekOutOfRange() {
        assertThatThrownBy(() -> new KdcaWeek(2025, 53)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new KdcaWeek(2025, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("날짜가 든 주 — 연말은 다음 해 1주다 (2025-12-28 은 2026년 1주, 2025-12-27 은 2025년 52주)")
    void containingCrossesTheYearBoundary() {
        assertThat(KdcaWeek.containing(LocalDate.of(2026, 1, 1))).isEqualTo(new KdcaWeek(2026, 1));
        assertThat(KdcaWeek.containing(LocalDate.of(2025, 12, 28))).isEqualTo(new KdcaWeek(2026, 1));
        assertThat(KdcaWeek.containing(LocalDate.of(2025, 12, 27))).isEqualTo(new KdcaWeek(2025, 52));
        assertThat(KdcaWeek.containing(LocalDate.of(2022, 12, 31))).isEqualTo(new KdcaWeek(2022, 53));
    }

    @Test
    @DisplayName("2026-10-02(금)은 2026년 40주다 — 40주는 09-27 ~ 10-03 이고, 그때 공개된 마지막 주는 39주(09-20 ~ 09-26)였다")
    void containingWeekOfSentinelObservation() {
        KdcaWeek week = KdcaWeek.containing(LocalDate.of(2026, 10, 2));

        assertThat(week).isEqualTo(new KdcaWeek(2026, 40));
        assertThat(week.start()).isEqualTo(LocalDate.of(2026, 9, 27));
        assertThat(new KdcaWeek(2026, 39).start()).isEqualTo(LocalDate.of(2026, 9, 20));
    }

    @Test
    @DisplayName("주의 모든 날(일 ~ 토)이 같은 주다")
    void containingIsStableWithinAWeek() {
        KdcaWeek week = new KdcaWeek(2026, 40);

        for (int day = 0; day < 7; day++) {
            assertThat(KdcaWeek.containing(week.start().plusDays(day))).isEqualTo(week);
        }
    }

    @Test
    @DisplayName("n 주 전 — 연도 경계(2026년 2주 − 3주 = 2025년 51주)와 53주가 있는 해(2023년 1주 − 1주 = 2022년 53주)를 넘어간다")
    void minusWeeksCrossesYearBoundaryAnd53WeekYears() {
        assertThat(new KdcaWeek(2026, 2).minusWeeks(3)).isEqualTo(new KdcaWeek(2025, 51));
        assertThat(new KdcaWeek(2023, 1).minusWeeks(1)).isEqualTo(new KdcaWeek(2022, 53));
        assertThat(new KdcaWeek(2023, 2).minusWeeks(2)).isEqualTo(new KdcaWeek(2022, 53));
        assertThat(new KdcaWeek(2026, 40).minusWeeks(0)).isEqualTo(new KdcaWeek(2026, 40));
        assertThat(new KdcaWeek(2026, 40).minusWeeks(7)).isEqualTo(new KdcaWeek(2026, 33));
    }
}
