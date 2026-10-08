package com.sneezecast.domainlayer.aggregate.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sneezecast.domainlayer.aggregate.domain.enums.AggregateLevel;
import com.sneezecast.domainlayer.aggregate.domain.enums.InsufficientReason;
import com.sneezecast.domainlayer.aggregate.domain.model.AggregateRule.WeekState;
import com.sneezecast.domainlayer.report.domain.model.ReportWeek;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * 단계 판정 규칙 (entity-design §2-2). 경계값을 모두 정수로 못 박는다 — 정확히 3%p · 8%p · 50% · 100명에서 어느 쪽인지가 계약이다.
 */
class AggregateRuleTest {

    private static final AggregateRule RULE = new AggregateRule("2026-10-08", 100, 50, 3, 8, 4, 8);
    private static final String DISTRICT = "11240660";
    private static final ReportWeek WEEK = ReportWeek.parse("2027-W03");
    private static final LocalDateTime AT = LocalDateTime.of(2027, 1, 18, 0, 10);

    @Test
    @DisplayName("참여 99명은 LOW_SAMPLE, 100명은 표본 충족이다 — 기준선 없이도 이유는 LOW_SAMPLE 이 먼저다")
    void lowSampleBoundary() {
        assertThat(judge(99, 99, List.of())).isEqualTo(AggregateJudgement.insufficient(InsufficientReason.LOW_SAMPLE));
        assertThat(judge(100, 10, List.of()).insufficientReason()).isEqualTo(InsufficientReason.NO_BASELINE);
    }

    @ParameterizedTest(name = "전주 {0}명 → 이번 주 {1}명 = UNSTABLE {2}")
    @CsvSource({
        // 감소 50% 정확히 → 급변
        "200, 100, true",
        "200, 101, false",
        // 증가 50% 정확히 → 급변
        "100, 150, true",
        "100, 149, false"
    })
    @DisplayName("전주 대비 참여 변화가 50% 이상이면 UNSTABLE 이다 — 정확히 50% 포함")
    void unstableBoundary(int previous, int current, boolean unstable) {
        List<DistrictWeeklyAggregate> history = new ArrayList<>(baseline(2, 5, 100, 10));
        history.add(row(1, previous, 10, true, null));

        AggregateJudgement judgement = judge(current, 10, history);

        assertThat(judgement.insufficientReason() == InsufficientReason.UNSTABLE).isEqualTo(unstable);
    }

    @Test
    @DisplayName("전주가 표본 미달(99명)이거나 없으면 급변을 보지 않는다")
    void unstableIsSkippedWithoutUsablePreviousWeek() {
        List<DistrictWeeklyAggregate> withSmallPrevious = new ArrayList<>(baseline(2, 5, 100, 10));
        withSmallPrevious.add(row(1, 99, 10, true, InsufficientReason.LOW_SAMPLE));

        assertThat(judge(1000, 100, withSmallPrevious).level()).isEqualTo(AggregateLevel.NORMAL);
        assertThat(judge(1000, 100, baseline(2, 5, 100, 10)).level()).isEqualTo(AggregateLevel.NORMAL);
    }

    @Test
    @DisplayName("전주는 마감 전이어도 급변 판정에 쓴다")
    void unfinalizedPreviousWeekStillCountsForUnstable() {
        List<DistrictWeeklyAggregate> history = new ArrayList<>(baseline(2, 5, 100, 10));
        history.add(row(1, 300, 30, false, null));

        assertThat(judge(100, 10, history).insufficientReason()).isEqualTo(InsufficientReason.UNSTABLE);
    }

    @Test
    @DisplayName("진행 중인 주는 급변을 보지 않는다 — 전주 300명 · 이번 주 150명이 마감 판정이면 UNSTABLE, 진행 중이면 기준선 대비로 판정한다")
    void unstableAppliesOnlyToClosedWeek() {
        List<DistrictWeeklyAggregate> history = new ArrayList<>(baseline(2, 5, 100, 10));
        history.add(row(1, 300, 30, true, null));

        assertThat(judge(WeekState.CLOSED, 150, 15, history)).isEqualTo(AggregateJudgement.insufficient(InsufficientReason.UNSTABLE));
        // 기준선 1 ~ 4주 전: 300 + 100 × 3 = 600명 중 60명 (10%), 이번 주 10% → NORMAL
        assertThat(judge(WeekState.IN_PROGRESS, 150, 15, history)).isEqualTo(AggregateJudgement.judged(AggregateLevel.NORMAL, 600, 60));
    }

    @Test
    @DisplayName("진행 중인 주도 LOW_SAMPLE · NO_BASELINE 은 그대로 본다")
    void inProgressStillChecksSampleAndBaseline() {
        List<DistrictWeeklyAggregate> previousOnly = List.of(row(1, 300, 30, true, null));

        assertThat(judge(WeekState.IN_PROGRESS, 99, 0, previousOnly).insufficientReason()).isEqualTo(InsufficientReason.LOW_SAMPLE);
        assertThat(judge(WeekState.IN_PROGRESS, 150, 15, previousOnly).insufficientReason()).isEqualTo(InsufficientReason.NO_BASELINE);
    }

    @Test
    @DisplayName("판정 순서가 고정이다 — 표본 미달이 급변보다, 급변이 기준선 없음보다 먼저다")
    void judgementOrderIsFixed() {
        List<DistrictWeeklyAggregate> previousOnly = List.of(row(1, 300, 30, true, null));

        // 참여 99명 + 전주 300명(급변) → LOW_SAMPLE
        assertThat(judge(99, 0, previousOnly).insufficientReason()).isEqualTo(InsufficientReason.LOW_SAMPLE);
        // 전주 행만 있어 기준선이 없고 급변이기도 하다 → UNSTABLE
        assertThat(judge(150, 15, previousOnly).insufficientReason()).isEqualTo(InsufficientReason.UNSTABLE);
    }

    @Test
    @DisplayName("기준선 주가 3개면 NO_BASELINE, 4개면 판정한다")
    void baselineNeedsFourWeeks() {
        assertThat(judge(100, 10, baseline(1, 3, 100, 10))).isEqualTo(AggregateJudgement.insufficient(InsufficientReason.NO_BASELINE));
        assertThat(judge(100, 10, baseline(1, 4, 100, 10))).isEqualTo(AggregateJudgement.judged(AggregateLevel.NORMAL, 400, 40));
    }

    @Test
    @DisplayName("직전 8주 밖(9주 전) 행은 기준선에 넣지 않는다")
    void rowsOutsideLookbackAreIgnored() {
        List<DistrictWeeklyAggregate> history = new ArrayList<>(baseline(6, 8, 100, 10));
        history.add(row(9, 100, 10, true, null));

        assertThat(judge(100, 10, history).insufficientReason()).isEqualTo(InsufficientReason.NO_BASELINE);

        history.add(row(5, 100, 10, true, null));
        assertThat(judge(100, 10, history).level()).isEqualTo(AggregateLevel.NORMAL);
    }

    @Test
    @DisplayName("UNSTABLE · 미마감 · 표본 미달 주는 기준선에서 빠지고, 그 대신 더 앞의 주를 쓴다")
    void unusableWeeksAreSkipped() {
        List<DistrictWeeklyAggregate> history = new ArrayList<>();
        // 1주 전 미마감, 2주 전 UNSTABLE, 3주 전 표본 미달 — 모두 건너뛴다.
        history.add(row(1, 100, 50, false, null));
        history.add(row(2, 200, 100, true, InsufficientReason.UNSTABLE));
        history.add(row(3, 99, 50, true, InsufficientReason.LOW_SAMPLE));
        history.addAll(baseline(4, 6, 100, 10));

        assertThat(judge(100, 10, history).insufficientReason()).isEqualTo(InsufficientReason.NO_BASELINE);

        history.add(row(7, 200, 20, true, null));
        assertThat(judge(100, 10, history)).isEqualTo(AggregateJudgement.judged(AggregateLevel.NORMAL, 500, 50));
    }

    @Test
    @DisplayName("기준선 자격이 되는 주가 더 있으면 최근 4주만 합산한다 — 그 주의 판정이 NO_BASELINE 이어도 기준선은 될 수 있다")
    void mostRecentFourWeeksAreUsed() {
        List<DistrictWeeklyAggregate> history = new ArrayList<>();
        history.add(row(1, 100, 10, true, InsufficientReason.NO_BASELINE));
        history.add(row(2, 200, 20, true, null));
        history.add(row(3, 300, 30, true, null));
        history.add(row(4, 400, 40, true, null));
        history.add(row(5, 500, 500, true, null));

        assertThat(judge(100, 10, history)).isEqualTo(AggregateJudgement.judged(AggregateLevel.NORMAL, 1000, 100));
    }

    @ParameterizedTest(name = "이번 주 {0}/100, 기준선 {1}/400 → {2}")
    @CsvSource({
        // 기준선 10%: 13% = 정확히 3%p → SLIGHT, 12% → NORMAL, 18% = 정확히 8%p → HIGH, 17% → SLIGHT, 감소 → NORMAL
        "13, 40, SLIGHT",
        "12, 40, NORMAL",
        "18, 40, HIGH",
        "17, 40, SLIGHT",
        "0, 40, NORMAL",
        // 부동소수로는 0.35 − 0.32 = 0.0299… 라 놓치는 경계 — 정수 비교로는 정확히 3%p 다.
        "35, 128, SLIGHT",
        "40, 128, HIGH"
    })
    @DisplayName("증상 비율 − 기준선 비율이 3%p 이상이면 SLIGHT, 8%p 이상이면 HIGH — 정확한 경계 포함")
    void deltaBoundaries(int symptomatic, int baselineSymptomatic, AggregateLevel expected) {
        List<DistrictWeeklyAggregate> history = new ArrayList<>();
        for (int back = 1; back <= 4; back++) {
            history.add(row(back, 100, baselineSymptomatic / 4, true, null));
        }

        assertThat(judge(100, symptomatic, history)).isEqualTo(AggregateJudgement.judged(expected, 400, baselineSymptomatic / 4 * 4));
    }

    @Test
    @DisplayName("참여자 수가 달라도 비율로 비교한다 — 기준선 합산 비율 10%, 이번 주 300명 중 39명(13%)은 SLIGHT, 38명은 NORMAL")
    void comparesRatesNotCounts() {
        // 전주 행을 두지 않아 급변(100명 → 300명)을 보지 않게 한다.
        assertThat(judge(300, 39, baseline(2, 5, 100, 10)).level()).isEqualTo(AggregateLevel.SLIGHT);
        assertThat(judge(300, 38, baseline(2, 5, 100, 10)).level()).isEqualTo(AggregateLevel.NORMAL);
    }

    @Test
    @DisplayName("대상 주 자신 · 이후 주 행은 무시한다 — 넉넉히 넘겨도 된다")
    void targetAndLaterWeeksAreIgnored() {
        List<DistrictWeeklyAggregate> history = new ArrayList<>(baseline(1, 3, 100, 10));
        history.add(row(0, 100, 10, true, null));
        history.add(row(-1, 100, 10, true, null));

        assertThat(judge(100, 10, history).insufficientReason()).isEqualTo(InsufficientReason.NO_BASELINE);
    }

    @Test
    @DisplayName("다른 행정동 행이 섞이거나 범위 안에 같은 주 행이 둘이면 거부한다 — 호출자 조회가 틀렸다")
    void rejectsMixedOrDuplicateHistory() {
        DistrictWeeklyAggregate other = DistrictWeeklyAggregate.newAggregate(99L, calculation("11230510", WEEK.minusWeeks(1), 100, 10));

        assertThatThrownBy(() -> judge(100, 10, List.of(other))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> judge(100, 10, List.of(row(1, 100, 10, true, null), row(1, 100, 10, true, null))))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("calculate 는 판정에 이 규칙 버전 · 수치 · 시각을 묶는다")
    void calculateCarriesRuleVersion() {
        AggregateCounts counts = new AggregateCounts(120, 20, 15, 8, 3);

        AggregateCalculation calculation = RULE.calculate(DISTRICT, WEEK, WeekState.CLOSED, counts, baseline(1, 4, 100, 10), AT);

        assertThat(calculation.ruleVersion()).isEqualTo("2026-10-08");
        assertThat(calculation.counts()).isEqualTo(counts);
        assertThat(calculation.districtCode()).isEqualTo(DISTRICT);
        assertThat(calculation.isoWeek()).isEqualTo(WEEK);
        assertThat(calculation.calculatedAt()).isEqualTo(AT);
        // 20/120 = 16.7% − 10% = 6.7%p
        assertThat(calculation.judgement()).isEqualTo(AggregateJudgement.judged(AggregateLevel.SLIGHT, 400, 40));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
        "aggregate.rule-version            | ' ' | 100 | 50 | 3 | 8   | 4 | 8",
        "aggregate.rule-version            | 2026-10-08-too-long-x | 100 | 50 | 3 | 8 | 4 | 8",
        "aggregate.min-sample              | v1  | 0   | 50 | 3 | 8   | 4 | 8",
        "aggregate.unstable-change-percent | v1  | 100 | 0  | 3 | 8   | 4 | 8",
        "aggregate.slight-delta-pp         | v1  | 100 | 50 | 0 | 8   | 4 | 8",
        "aggregate.high-delta-pp           | v1  | 100 | 50 | 3 | 3   | 4 | 8",
        "aggregate.high-delta-pp           | v1  | 100 | 50 | 3 | 101 | 4 | 8",
        "aggregate.baseline-weeks          | v1  | 100 | 50 | 3 | 8   | 0 | 8",
        "aggregate.baseline-lookback-weeks | v1  | 100 | 50 | 3 | 8   | 4 | 3"
    })
    @DisplayName("잘못된 규칙 값은 설정 키를 담아 거부한다")
    void rejectsInvalidSettings(String key, String version, int minSample, int unstable, int slight, int high, int baselineWeeks, int lookback) {
        assertThatThrownBy(() -> new AggregateRule(version.isBlank() ? version : version.strip(), minSample, unstable, slight, high, baselineWeeks, lookback))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining(key);
    }

    @Test
    @DisplayName("rule-version 은 20자까지 받고, lookback 은 baseline-weeks 와 같아도 된다")
    void acceptsBoundarySettings() {
        assertThat(new AggregateRule("x".repeat(20), 1, 1, 1, 100, 4, 4).ruleVersion()).hasSize(20);
    }

    // 마감 판정 — 판정 순서를 모두 본다.
    private static AggregateJudgement judge(int participants, int symptomatic, List<DistrictWeeklyAggregate> history) {
        return judge(WeekState.CLOSED, participants, symptomatic, history);
    }

    private static AggregateJudgement judge(WeekState weekState, int participants, int symptomatic, List<DistrictWeeklyAggregate> history) {
        return RULE.judge(DISTRICT, WEEK, weekState, new AggregateCounts(participants, symptomatic, symptomatic, 0, 0), history);
    }

    // from 주 전 ~ to 주 전, 모두 마감 · 정상 판정
    private static List<DistrictWeeklyAggregate> baseline(int from, int to, int participants, int symptomatic) {
        List<DistrictWeeklyAggregate> rows = new ArrayList<>();
        for (int back = from; back <= to; back++) {
            rows.add(row(back, participants, symptomatic, true, null));
        }
        return rows;
    }

    // back 주 전 행. back 이 0 이하면 대상 주 · 이후 주다.
    private static DistrictWeeklyAggregate row(int back, int participants, int symptomatic, boolean finalized, InsufficientReason reason) {
        ReportWeek week = back >= 0 ? WEEK.minusWeeks(back) : ReportWeek.parse("2027-W04");
        AggregateJudgement judgement = reason == null
            ? AggregateJudgement.judged(AggregateLevel.NORMAL, 400, 40)
            : AggregateJudgement.insufficient(reason);
        AggregateCalculation calculation = new AggregateCalculation(DISTRICT, week, new AggregateCounts(participants, symptomatic, symptomatic, 0, 0),
            judgement, "2026-10-08", AT);
        DistrictWeeklyAggregate aggregate = DistrictWeeklyAggregate.newAggregate(back + 1000L, calculation);
        return finalized ? asFinalized(aggregate) : aggregate;
    }

    static DistrictWeeklyAggregate asFinalized(DistrictWeeklyAggregate a) {
        return new DistrictWeeklyAggregate(a.id(), a.districtCode(), a.isoWeek(), a.participantCount(), a.symptomaticCount(), a.respiratoryCount(),
            a.entericCount(), a.revisedReportCount(), a.level(), a.insufficientReason(), a.baselineParticipantCount(), a.baselineSymptomaticCount(),
            a.ruleVersion(), a.calculatedAt(), AT, a.createdAt(), a.updatedAt());
    }

    private static AggregateCalculation calculation(String district, ReportWeek week, int participants, int symptomatic) {
        return new AggregateCalculation(district, week, new AggregateCounts(participants, symptomatic, symptomatic, 0, 0),
            AggregateJudgement.judged(AggregateLevel.NORMAL, 400, 40), "2026-10-08", AT);
    }
}
