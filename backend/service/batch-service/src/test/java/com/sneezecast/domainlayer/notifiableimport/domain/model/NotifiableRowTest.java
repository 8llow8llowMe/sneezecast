package com.sneezecast.domainlayer.notifiableimport.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 행 record 가 적재 대상 컬럼 제약(entity-design §3-2)을 원천 경계에서 지키는지 본다. */
class NotifiableRowTest {

    @Test
    @DisplayName("감염병 키 · 이름은 100자까지, 101자는 거절한다")
    void diseaseNameAndKeyLimitIs100() {
        assertThatCode(() -> weekly("가".repeat(100), "가".repeat(100), null, null)).doesNotThrowAnyException();
        assertThatThrownBy(() -> weekly("가".repeat(101), "수두", null, null)).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("diseaseKey exceeds 100");
        assertThatThrownBy(() -> region("서울", "수두", "가".repeat(101), null, null)).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("diseaseName exceeds 100");
    }

    @Test
    @DisplayName("분류는 20자까지(null 허용), 21자는 거절한다")
    void diseaseGroupLimitIs20() {
        assertThatCode(() -> weekly("수두", "수두", "가".repeat(20), null)).doesNotThrowAnyException();
        assertThatCode(() -> weekly("수두", "수두", null, null)).doesNotThrowAnyException();
        assertThatThrownBy(() -> region("서울", "수두", "수두", "가".repeat(21), null)).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("diseaseGroup exceeds 20");
    }

    @Test
    @DisplayName("시도 이름은 30자까지, 31자는 거절한다 — 메시지에 값을 싣지 않는다")
    void sidoNameLimitIs30() {
        assertThatCode(() -> region("가".repeat(30), "수두", "수두", null, null)).doesNotThrowAnyException();
        assertThatThrownBy(() -> region("가".repeat(31), "수두", "수두", null, null)).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("sidoName exceeds 30")
            .hasMessageNotContaining("가");
    }

    @ParameterizedTest(name = "value={0}")
    @ValueSource(strings = {"9999999999.99", "1.500", "0", "0.00", "1041"})
    @DisplayName("DECIMAL(12,2) 에 그대로 들어가는 0 이상의 값은 받는다 — 뒤의 0 은 자리로 치지 않는다")
    void acceptsValuesWithinDecimal12Scale2(String value) {
        assertThatCode(() -> weekly("수두", "수두", null, new BigDecimal(value))).doesNotThrowAnyException();
        assertThatCode(() -> region("서울", "수두", "수두", null, new BigDecimal(value))).doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "value={0}")
    @ValueSource(strings = {"0.125", "-1", "-0.01", "1E+12", "10000000000", "10000000000.00"})
    @DisplayName("음수 · 소수 셋째 자리 · 정수부 11자리 이상은 거절한다 — MySQL 이 조용히 반올림 · 실패하기 전에")
    void rejectsValuesOutsideDecimal12Scale2(String value) {
        assertThatThrownBy(() -> weekly("수두", "수두", null, new BigDecimal(value))).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("at most 2 decimal places");
        assertThatThrownBy(() -> region("서울", "수두", "수두", null, new BigDecimal(value))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("값이 null 이면 받는다 — 원천이 비운 값은 0 과 구분한다")
    void acceptsNullValue() {
        assertThat(weekly("수두", "수두", null, null).value()).isNull();
    }

    private static NotifiableWeeklyRow weekly(String diseaseKey, String diseaseName, String group, BigDecimal value) {
        return new NotifiableWeeklyRow(2026, 1, diseaseKey, diseaseName, group, value);
    }

    private static NotifiableRegionRow region(String sidoName, String diseaseKey, String diseaseName, String group, BigDecimal value) {
        return new NotifiableRegionRow(2026, "01", sidoName, diseaseKey, diseaseName, group, value);
    }
}
