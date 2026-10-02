package com.sneezecast.domainlayer.notifiableimport.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sneezecast.domainlayer.notifiableimport.domain.model.NotifiableRequest.Kind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class NotifiableRequestTest {

    @Test
    @DisplayName("request_key 는 조회 조건만 담는다 — 주별 전국 · 시도 발생 수 · 시도 10만 명당")
    void buildsRequestKeys() {
        assertThat(NotifiableRequest.weekly(2026).requestKey()).isEqualTo("notifiable:periodBasic:week:2026");
        assertThat(NotifiableRequest.region(2026, NotifiableRegionMeasure.CASE_COUNT, "01").requestKey())
            .isEqualTo("notifiable:region:count:2026:sido=01");
        assertThat(NotifiableRequest.region(2025, NotifiableRegionMeasure.INCIDENCE_PER_100K, "18").requestKey())
            .isEqualTo("notifiable:region:per100k:2025:sido=18");
    }

    @ParameterizedTest(name = "year={0}")
    @ValueSource(ints = {999, 10000, 0, -2026})
    @DisplayName("연도는 네 자리여야 한다 — 원천이 '2026년' 으로 적는다")
    void rejectsNonFourDigitYear(int year) {
        assertThatThrownBy(() -> NotifiableRequest.weekly(year)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("year");
    }

    @ParameterizedTest(name = "sidoCode={0}")
    @NullSource
    @ValueSource(strings = {"00", "1", "001", "ab", " 1"})
    @DisplayName("시도 코드는 두 자리 숫자이고 전국(00)이 아니어야 한다")
    void rejectsInvalidSidoCode(String sidoCode) {
        assertThatThrownBy(() -> NotifiableRequest.region(2026, NotifiableRegionMeasure.CASE_COUNT, sidoCode))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("sidoCode");
    }

    @Test
    @DisplayName("시도 연별은 지표가 있어야 하고, 주별 전국은 지표 · 시도가 없어야 한다")
    void enforcesKindSpecificFields() {
        assertThatThrownBy(() -> NotifiableRequest.region(2026, null, "01")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new NotifiableRequest(Kind.PERIOD_BASIC_WEEKLY, 2026, NotifiableRegionMeasure.CASE_COUNT, null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NotifiableRequest(Kind.PERIOD_BASIC_WEEKLY, 2026, null, "01"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NotifiableRequest(null, 2026, null, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("지표의 searchType 은 원천 값(1 발생 수 · 2 10만 명당)이다")
    void measureSearchTypesMatchSource() {
        assertThat(NotifiableRegionMeasure.CASE_COUNT.getSearchType()).isEqualTo(1);
        assertThat(NotifiableRegionMeasure.INCIDENCE_PER_100K.getSearchType()).isEqualTo(2);
    }
}
