package com.sneezecast.domainlayer.sentinelimport.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sneezecast.domainlayer.official.domain.enums.OfficialAgeGroup;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 행 record 는 적재 대상 컬럼 제약(entity-design §3-2)을 원천 경계에서 지킨다 — DB 가 자르거나 반올림하기 전에 드러낸다. */
class SentinelRowTest {

    @Test
    @DisplayName("컬럼 한계 안의 값은 그대로 받는다 — 이름 100자 · 분류 20자 · 9,999,999,999.99 · 53주 · 값 null")
    void keepsValuesAtColumnLimits() {
        SentinelPathogenRow row = new SentinelPathogenRow(2026, 53, "ND0601", "가".repeat(100), "나".repeat(20), new BigDecimal("9999999999.99"));

        assertThat(row.diseaseName()).hasSize(100);
        assertThat(row.diseaseGroup()).hasSize(20);
        assertThat(new SentinelPathogenRow(2026, 1, "TOTAL", "계", "계", null).value()).isNull();
        assertThat(new SentinelIliRow(2026, 53, OfficialAgeGroup.AGE_65_PLUS, new BigDecimal("1.50")).value()).isEqualByComparingTo("1.5");
    }

    @ParameterizedTest(name = "week={0}")
    @ValueSource(ints = {0, 54})
    @DisplayName("주차는 1 ~ 53 이다 (53주가 있는 해가 있다)")
    void rejectsWeekOutOfRange(int week) {
        assertThatThrownBy(() -> new SentinelPathogenRow(2026, week, "TOTAL", "계", "계", BigDecimal.ONE))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("week");
        assertThatThrownBy(() -> new SentinelIliRow(2026, week, OfficialAgeGroup.AGE_0, BigDecimal.ONE))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("week");
    }

    @Test
    @DisplayName("이름 · 분류는 비울 수 없고 컬럼 길이를 넘지 못한다 — 메시지에 값을 싣지 않는다")
    void rejectsBlankOrTooLongText() {
        assertThatThrownBy(() -> new SentinelPathogenRow(2026, 1, " ", "계", "계", BigDecimal.ONE))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("diseaseKey");
        assertThatThrownBy(() -> new SentinelPathogenRow(2026, 1, "TOTAL", "가".repeat(101), "계", BigDecimal.ONE))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("diseaseName exceeds 100");
        assertThatThrownBy(() -> new SentinelPathogenRow(2026, 1, "TOTAL", "계", "가".repeat(21), BigDecimal.ONE))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("diseaseGroup exceeds 20").hasMessageNotContaining("가");
    }

    @ParameterizedTest(name = "value={0}")
    @ValueSource(strings = {"-1", "0.125", "1E+12"})
    @DisplayName("값은 DECIMAL(12,2) 에 그대로 들어가는 0 이상이어야 한다 — 자르거나 반올림하지 않는다")
    void rejectsValuesOutsideColumnRange(String value) {
        BigDecimal number = new BigDecimal(value);

        assertThatThrownBy(() -> new SentinelPathogenRow(2026, 1, "TOTAL", "계", "계", number)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SentinelIliRow(2026, 1, OfficialAgeGroup.AGE_0, number)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("연령대는 비울 수 없다")
    void rejectsNullAgeGroup() {
        assertThatThrownBy(() -> new SentinelIliRow(2026, 1, null, BigDecimal.ONE)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("조회 결과 — 원천 한 행이 열 수만큼의 행이 되므로 rawRowCount 가 rows 보다 작아도 된다. 목록은 복사해 바꿀 수 없다")
    void fetchKeepsCountsAndCopiesRows() {
        List<SentinelPathogenRow> rows = new ArrayList<>(List.of(
            new SentinelPathogenRow(2026, 39, "TOTAL", "계", "계", BigDecimal.TEN),
            new SentinelPathogenRow(2026, 39, "ND0708", "마이코플라즈마균", "세균", null)));

        SentinelFetch<SentinelPathogenRow> fetch = new SentinelFetch<>(rows, "a".repeat(64), 10, 1, 2, 1, 0);
        rows.clear();

        assertThat(fetch.rows()).hasSize(2);
        assertThatThrownBy(() -> fetch.rows().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("조회 결과 — 해시는 소문자 64자이고, 호출 수는 1 이상, 결측 수는 행 수 이하, 집계 중 수는 0 이상이다")
    void fetchRejectsInvalidCounts() {
        List<SentinelIliRow> rows = List.of(new SentinelIliRow(2026, 36, OfficialAgeGroup.AGE_0, null));
        String sha = "0".repeat(64);

        assertThatThrownBy(() -> new SentinelFetch<>(rows, "A".repeat(64), 1, 1, 2, 0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SentinelFetch<>(rows, null, 1, 1, 2, 0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SentinelFetch<>(rows, sha, 1, 1, 0, 0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SentinelFetch<>(rows, sha, 1, 1, 2, 2, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SentinelFetch<>(rows, sha, 1, 1, 2, 0, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SentinelFetch<>(rows, sha, -1, 1, 2, 0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new SentinelFetch<>(rows, sha, 1, 7, 2, 1, 7).pendingCount()).isEqualTo(7);
    }
}
