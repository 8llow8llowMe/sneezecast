package com.sneezecast.domainlayer.notifiableimport.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sneezecast.global.properties.NotifiableImportProperties;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NotifiableImportPlanTest {

    @Test
    @DisplayName("기본 시도 18개면 74건이다 — 주별 2 + 시도 연별 2년 × 2지표 × 18")
    void plansSeventyFourRequestsForDefaultSidoCodes() {
        NotifiableImportPlan plan = NotifiableImportPlan.of(2026, NotifiableImportProperties.DEFAULT_SIDO_CODES);

        assertThat(plan.size()).isEqualTo(74);
        assertThat(plan.requests()).extracting(NotifiableRequest::requestKey).doesNotHaveDuplicates()
            .allSatisfy(key -> assertThat(key.length()).isLessThanOrEqualTo(200));
    }

    @Test
    @DisplayName("순서는 주별(올해 → 전년) → 올해 시도(발생 수 시도 순 → 10만 명당 시도 순) → 전년 시도(같은 순)이고, 설정의 시도 순서를 따른다")
    void ordersRequestsWeeklyFirstThenRegionByYearMeasureSido() {
        NotifiableImportPlan plan = NotifiableImportPlan.of(2026, List.of("18", "01"));

        assertThat(plan.requests()).extracting(NotifiableRequest::requestKey).containsExactly(
            "notifiable:periodBasic:week:2026",
            "notifiable:periodBasic:week:2025",
            "notifiable:region:count:2026:sido=18",
            "notifiable:region:count:2026:sido=01",
            "notifiable:region:per100k:2026:sido=18",
            "notifiable:region:per100k:2026:sido=01",
            "notifiable:region:count:2025:sido=18",
            "notifiable:region:count:2025:sido=01",
            "notifiable:region:per100k:2025:sido=18",
            "notifiable:region:per100k:2025:sido=01");
        assertThat(plan.requests().get(0)).isEqualTo(NotifiableRequest.weekly(2026));
        assertThat(plan.requests().get(4)).isEqualTo(NotifiableRequest.region(2026, NotifiableRegionMeasure.INCIDENCE_PER_100K, "18"));
    }

    @Test
    @DisplayName("같은 시도가 두 번 있으면 같은 요청이 겹치므로 만들지 않는다")
    void rejectsDuplicateSidoCodes() {
        assertThatThrownBy(() -> NotifiableImportPlan.of(2026, List.of("01", "01")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("notifiable:region:count:2026:sido=01");
    }

    @Test
    @DisplayName("목록은 바꿀 수 없다")
    void requestsAreImmutable() {
        NotifiableImportPlan plan = NotifiableImportPlan.of(2026, List.of("01"));

        assertThatThrownBy(() -> plan.requests().add(NotifiableRequest.weekly(2024))).isInstanceOf(UnsupportedOperationException.class);
    }
}
