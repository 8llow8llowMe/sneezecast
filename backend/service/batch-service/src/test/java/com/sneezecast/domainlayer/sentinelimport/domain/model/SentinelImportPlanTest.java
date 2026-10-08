package com.sneezecast.domainlayer.sentinelimport.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sneezecast.domainlayer.official.domain.model.KdcaWeek;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 주차는 질병관리청 주차(일요일 시작, 1월 1일이 든 주가 1주)다 — 2026-10-02 는 2026년 40주다 (data-api-analysis §5). */
class SentinelImportPlanTest {

    private static final int RECENT_WEEKS = 8;

    @Test
    @DisplayName("평시는 3건이다 — 급성호흡기 → 장관 → 인플루엔자(현재 절기) 순서이고, 주 범위는 실행 주까지 최근 8주다")
    void plansThreeRequestsOutsideSeasonStart() {
        SentinelImportPlan plan = SentinelImportPlan.of(LocalDate.of(2026, 6, 5), RECENT_WEEKS);

        assertThat(plan.size()).isEqualTo(3);
        assertThat(plan.requests()).extracting(SentinelRequest::requestKey).containsExactly(
            // 2026-06-05 는 2026년 23주 → 16 ~ 23주. 23주는 36주보다 앞이라 절기는 2025–2026 이다.
            "sentinel:ari:2026-16~2026-23",
            "sentinel:enteric:2026-16~2026-23",
            "sentinel:influenza:2025-2026");
        assertThat(plan.requests().get(0)).isEqualTo(SentinelRequest.weeks(SentinelProgram.ARI, new KdcaWeek(2026, 16), new KdcaWeek(2026, 23)));
    }

    @Test
    @DisplayName("최근 8주 창이 지난 절기 주에 걸치면 지난 절기까지 4건이다 — 2026-10-02(40주)의 창은 33 ~ 40주다")
    void plansPreviousSeasonNearSeasonStart() {
        SentinelImportPlan plan = SentinelImportPlan.of(LocalDate.of(2026, 10, 2), RECENT_WEEKS);

        assertThat(plan.size()).isEqualTo(4);
        assertThat(plan.requests()).extracting(SentinelRequest::requestKey).containsExactly(
            "sentinel:ari:2026-33~2026-40",
            "sentinel:enteric:2026-33~2026-40",
            "sentinel:influenza:2026-2027",
            "sentinel:influenza:2025-2026");
    }

    @ParameterizedTest(name = "{0}주")
    @ValueSource(ints = {36, 42})
    @DisplayName("창(현재 − 7주 ~ 현재)이 35주 이전에 걸치는 36 ~ 42주는 4건이다")
    void plansFourRequestsWithinEightWeeksOfSeasonStart(int week) {
        SentinelImportPlan plan = SentinelImportPlan.of(new KdcaWeek(2026, week).start(), RECENT_WEEKS);

        assertThat(plan.size()).isEqualTo(4);
        assertThat(plan.requests().get(3)).isEqualTo(SentinelRequest.season(2025));
    }

    @ParameterizedTest(name = "{0}주")
    @ValueSource(ints = {35, 43, 44})
    @DisplayName("절기 시작 직전(35주)과 창이 새 절기 안에만 있는 43주부터는 3건이다")
    void plansThreeRequestsOutsideTheWindow(int week) {
        SentinelImportPlan plan = SentinelImportPlan.of(new KdcaWeek(2026, week).start(), RECENT_WEEKS);

        assertThat(plan.size()).isEqualTo(3);
        // 35주는 아직 2025–2026 절기 안이고, 43 · 44주는 2026–2027 절기다.
        assertThat(plan.requests().get(2)).isEqualTo(SentinelRequest.season(week == 35 ? 2025 : 2026));
    }

    @Test
    @DisplayName("최근 8주가 연도를 넘으면 범위도 연도를 넘는다 — 급성호흡기 · 장관은 한 번에 받는다")
    void weekRangeCrossesTheYearBoundary() {
        SentinelImportPlan plan = SentinelImportPlan.of(LocalDate.of(2026, 1, 9), RECENT_WEEKS);

        // 2026-01-09 는 2026년 2주 → 2025년 47주 ~ 2026년 2주.
        assertThat(plan.requests().get(0)).isEqualTo(SentinelRequest.weeks(SentinelProgram.ARI, new KdcaWeek(2025, 47), new KdcaWeek(2026, 2)));
        assertThat(plan.requests().get(0).requestKey()).isEqualTo("sentinel:ari:2025-47~2026-02");
        assertThat(plan.size()).isEqualTo(3);
    }

    @Test
    @DisplayName("최근 1주면 범위가 실행 주 하나다")
    void supportsSingleWeekRange() {
        SentinelImportPlan plan = SentinelImportPlan.of(LocalDate.of(2026, 6, 5), 1);

        assertThat(plan.requests().get(0).requestKey()).isEqualTo("sentinel:ari:2026-23~2026-23");
        assertThat(plan.size()).isEqualTo(3);
    }

    @Test
    @DisplayName("request_key 는 겹치지 않고 200자 이내다")
    void requestKeysAreUniqueAndFitTheColumn() {
        SentinelImportPlan plan = SentinelImportPlan.of(LocalDate.of(2026, 10, 2), RECENT_WEEKS);

        assertThat(plan.requests()).extracting(SentinelRequest::requestKey).doesNotHaveDuplicates()
            .allSatisfy(key -> assertThat(key.length()).isLessThanOrEqualTo(SentinelRequest.MAX_REQUEST_KEY_LENGTH));
    }

    @Test
    @DisplayName("최근 주 수는 1 이상이고, 목록은 바꿀 수 없다")
    void rejectsNonPositiveRecentWeeksAndKeepsRequestsImmutable() {
        assertThatThrownBy(() -> SentinelImportPlan.of(LocalDate.of(2026, 10, 2), 0)).isInstanceOf(IllegalArgumentException.class);

        SentinelImportPlan plan = SentinelImportPlan.of(LocalDate.of(2026, 10, 2), RECENT_WEEKS);

        assertThatThrownBy(() -> plan.requests().add(SentinelRequest.season(2020))).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("같은 요청이 겹치면 만들지 않는다")
    void rejectsDuplicateRequests() {
        assertThatThrownBy(() -> new SentinelImportPlan(List.of(SentinelRequest.season(2026), SentinelRequest.season(2026))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("sentinel:influenza:2026-2027");
    }

    @Test
    @DisplayName("주 범위 요청은 from ≤ to 이고 절기가 없으며, 절기 요청은 인플루엔자만 · 주 범위 없이 네 자리 시작 연도로 만든다")
    void requestRejectsMixedOrInvalidRanges() {
        KdcaWeek week39 = new KdcaWeek(2026, 39);
        KdcaWeek week35 = new KdcaWeek(2026, 35);

        assertThatThrownBy(() -> SentinelRequest.weeks(SentinelProgram.ARI, week39, week35)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SentinelRequest.weeks(SentinelProgram.INFLUENZA_ILI, week35, week39)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SentinelRequest(SentinelProgram.ENTERIC, week35, week39, 2026)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SentinelRequest.weeks(SentinelProgram.ARI, null, week39)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> SentinelRequest.season(999)).isInstanceOf(IllegalArgumentException.class);
        assertThat(SentinelRequest.weeks(SentinelProgram.ENTERIC, week35, week35).requestKey()).isEqualTo("sentinel:enteric:2026-35~2026-35");
        assertThat(SentinelRequest.season(2026).seasonEndYear()).isEqualTo(2027);
    }

    @Test
    @DisplayName("최근 1주면 창이 실행 주 하나라 36주에도 지난 절기를 받지 않고, 2주면 35주에 걸쳐 받는다")
    void singleWeekWindowNeverReachesPreviousSeason() {
        assertThat(SentinelImportPlan.of(new KdcaWeek(2026, 36).start(), 1).size()).isEqualTo(3);
        assertThat(SentinelImportPlan.of(new KdcaWeek(2026, 36).start(), 2).size()).isEqualTo(4);
    }
}
