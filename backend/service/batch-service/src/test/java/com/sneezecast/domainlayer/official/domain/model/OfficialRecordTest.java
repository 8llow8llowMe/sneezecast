package com.sneezecast.domainlayer.official.domain.model;

import static com.sneezecast.domainlayer.official.OfficialFixtures.ariWeek;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sneezecast.domainlayer.official.domain.enums.OfficialAgeGroup;
import com.sneezecast.domainlayer.official.domain.enums.OfficialMetric;
import com.sneezecast.domainlayer.official.domain.enums.OfficialPeriodType;
import com.sneezecast.domainlayer.official.domain.enums.OfficialProgram;
import com.sneezecast.domainlayer.official.domain.enums.OfficialRegionLevel;
import com.sneezecast.domainlayer.official.domain.enums.OfficialSource;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OfficialRecordTest {

    @Test
    @DisplayName("전국 주별 행 · 시도 연별 행은 만들어진다")
    void acceptsWeeklyNationAndYearlySido() {
        assertThatCode(() -> ariWeek("ND0715", 38, "12")).doesNotThrowAnyException();
        assertThatCode(() -> yearlySido("01", 2025, LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31), 0)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("값이 빈 칸(null)인 행도 받는다 — 0 과 구분한다")
    void acceptsNullValue() {
        assertThat(ariWeek("ND0715", 38, null).metricValue()).isNull();
    }

    @Test
    @DisplayName("필수 문자열이 비면 거절한다")
    void rejectsBlankText() {
        assertThatThrownBy(() -> ariWeek(" ", 38, "1")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("diseaseKey");
        assertThatThrownBy(() -> withDiseaseGroup(""))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("diseaseGroup");
    }

    @Test
    @DisplayName("분류는 없어도(null) 된다")
    void acceptsNullDiseaseGroup() {
        assertThat(withDiseaseGroup(null).diseaseGroup()).isNull();
    }

    @Test
    @DisplayName("컬럼 길이를 넘으면 거절한다 — disease_key VARCHAR(100)")
    void rejectsTooLongText() {
        assertThatCode(() -> withDiseaseKey("가".repeat(100))).doesNotThrowAnyException();
        assertThatThrownBy(() -> withDiseaseKey("가".repeat(101)))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("diseaseKey must be at most 100");
    }

    private static OfficialRecord withDiseaseKey(String diseaseKey) {
        return new OfficialRecord(OfficialSource.KDCA_NOTIFIABLE, OfficialProgram.NOTIFIABLE, diseaseKey, "감염병", null,
            OfficialMetric.CASE_COUNT, OfficialAgeGroup.ALL, OfficialRegionLevel.NATION, "00", "전국", OfficialPeriodType.WEEK, 2026, 38,
            LocalDate.of(2026, 9, 13), LocalDate.of(2026, 9, 19), BigDecimal.ONE);
    }

    @Test
    @DisplayName("전국은 지역 코드 00 이고, 시도는 00 이 아니다")
    void nationRegionCodeIs00() {
        assertThatThrownBy(() -> nation("01")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("regionCode");
        assertThatThrownBy(() -> yearlySido("00", 2025, LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31), 0))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("regionCode");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 54})
    @DisplayName("WEEK 주차는 1 ~ 53 이다")
    void rejectsWeekOutOfRange(int week) {
        assertThatThrownBy(() -> weekly(week)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("periodWeek");
    }

    @Test
    @DisplayName("WEEK 53주는 받는다 (2022 처럼 53주가 있는 해)")
    void acceptsWeek53() {
        assertThatCode(() -> weekly(53)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("WEEK 기간이 질병관리청 주차와 맞으면 받는다 — 2026년 38주 = 09-13 ~ 09-19")
    void acceptsWeekMatchingKdcaWeek() {
        assertThatCode(() -> weekOf(2026, 38, LocalDate.of(2026, 9, 13), LocalDate.of(2026, 9, 19))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("52주인 해(2025)의 53주는 거절한다 — 그 해 주 수를 넘는다")
    void rejectsWeek53InYearOf52Weeks() {
        assertThatThrownBy(() -> weekOf(2025, 53, LocalDate.of(2025, 12, 28), LocalDate.of(2026, 1, 3)))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("KDCA week must be 1..52");
    }

    @Test
    @DisplayName("하루 어긋난 기간(ISO 주처럼 월요일 시작)은 거절한다")
    void rejectsWeekPeriodOffByOneDay() {
        assertThatThrownBy(() -> weekOf(2026, 38, LocalDate.of(2026, 9, 14), LocalDate.of(2026, 9, 20)))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("2026-09-13 ~ 2026-09-19");
    }

    @Test
    @DisplayName("YEAR 는 주차 0 이고 기간이 그 해 1/1 ~ 12/31 이다")
    void yearPeriodIsWholeYear() {
        assertThatThrownBy(() -> yearlySido("01", 2025, LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31), 1))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> yearlySido("01", 2025, LocalDate.of(2024, 12, 29), LocalDate.of(2025, 12, 31), 0))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> yearlySido("01", 2025, LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 30), 0))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("종료일이 시작일보다 앞이면 거절한다")
    void rejectsEndBeforeStart() {
        assertThatThrownBy(() -> new OfficialRecord(OfficialSource.KDCA_SENTINEL, OfficialProgram.ARI, "ND0715", "노로바이러스", null,
            OfficialMetric.CASE_COUNT, OfficialAgeGroup.ALL, OfficialRegionLevel.NATION, "00", "전국", OfficialPeriodType.WEEK, 2026, 38,
            LocalDate.of(2026, 9, 19), LocalDate.of(2026, 9, 13), BigDecimal.ONE))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("periodEnd");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "1.5", "1.50", "1.500", "9999999999.99", "12.3400"})
    @DisplayName("DECIMAL(12,2) 에 들어가는 값은 받는다 — 끝자리 0 으로 늘어난 scale 은 같은 값이다")
    void acceptsValueFittingDecimal(String value) {
        assertThatCode(() -> ariWeek("ND0715", 38, value)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.234", "0.001", "10000000000", "12345678901.5"})
    @DisplayName("소수 2자리를 넘거나 정수부가 10자리를 넘으면 거절한다 — DB 가 반올림 · 거절하기 전에 드러낸다")
    void rejectsValueNotFittingDecimal(String value) {
        assertThatThrownBy(() -> ariWeek("ND0715", 38, value))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("DECIMAL(12,2)");
    }

    @Test
    @DisplayName("자연키는 10 컬럼이 같으면 같다 — 이름 · 기간 · 값은 들어가지 않는다")
    void naturalKeyIgnoresNonKeyColumns() {
        assertThat(ariWeek("ND0715", 38, "1").naturalKey()).isEqualTo(ariWeek("ND0715", 38, "99").naturalKey());
        assertThat(ariWeek("ND0715", 38, "1").naturalKey()).isNotEqualTo(ariWeek("ND0715", 37, "1").naturalKey());
    }

    private static OfficialRecord weekOf(int year, int week, LocalDate start, LocalDate end) {
        return new OfficialRecord(OfficialSource.KDCA_SENTINEL, OfficialProgram.ARI, "ND0715", "노로바이러스", null, OfficialMetric.CASE_COUNT,
            OfficialAgeGroup.ALL, OfficialRegionLevel.NATION, "00", "전국", OfficialPeriodType.WEEK, year, week, start, end, BigDecimal.ONE);
    }

    /** 2022년(53주인 해) {@code week} 주. 기간은 53주(12-25 ~ 12-31)로 고정이라 53주 밖은 주차 범위 검사에서 먼저 걸린다. */
    private static OfficialRecord weekly(int week) {
        return new OfficialRecord(OfficialSource.KDCA_SENTINEL, OfficialProgram.ARI, "ND0715", "노로바이러스", null, OfficialMetric.CASE_COUNT,
            OfficialAgeGroup.ALL, OfficialRegionLevel.NATION, "00", "전국", OfficialPeriodType.WEEK, 2022, week, LocalDate.of(2022, 12, 25),
            LocalDate.of(2022, 12, 31), BigDecimal.ONE);
    }

    private static OfficialRecord nation(String regionCode) {
        return new OfficialRecord(OfficialSource.KDCA_SENTINEL, OfficialProgram.ARI, "ND0715", "노로바이러스", null, OfficialMetric.CASE_COUNT,
            OfficialAgeGroup.ALL, OfficialRegionLevel.NATION, regionCode, "전국", OfficialPeriodType.WEEK, 2026, 38, LocalDate.of(2026, 9, 13),
            LocalDate.of(2026, 9, 19), BigDecimal.ONE);
    }

    private static OfficialRecord withDiseaseGroup(String diseaseGroup) {
        return new OfficialRecord(OfficialSource.KDCA_SENTINEL, OfficialProgram.ARI, "ND0715", "노로바이러스", diseaseGroup,
            OfficialMetric.CASE_COUNT, OfficialAgeGroup.ALL, OfficialRegionLevel.NATION, "00", "전국", OfficialPeriodType.WEEK, 2026, 38,
            LocalDate.of(2026, 9, 13), LocalDate.of(2026, 9, 19), BigDecimal.ONE);
    }

    private static OfficialRecord yearlySido(String regionCode, int year, LocalDate start, LocalDate end, int week) {
        return new OfficialRecord(OfficialSource.KDCA_NOTIFIABLE, OfficialProgram.NOTIFIABLE, "결핵", "결핵", "제2급", OfficialMetric.CASE_COUNT,
            OfficialAgeGroup.ALL, OfficialRegionLevel.SIDO, regionCode, "서울", OfficialPeriodType.YEAR, year, week, start, end,
            new BigDecimal("1234"));
    }
}
