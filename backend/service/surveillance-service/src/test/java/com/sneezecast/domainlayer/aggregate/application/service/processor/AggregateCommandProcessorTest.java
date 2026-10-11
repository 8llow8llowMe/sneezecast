package com.sneezecast.domainlayer.aggregate.application.service.processor;

import static org.assertj.core.api.Assertions.assertThat;

import com.sneezecast.domainlayer.aggregate.AggregateH2TestSupport;
import com.sneezecast.domainlayer.aggregate.adapter.out.persistence.DistrictWeeklyAggregateRepositoryAdapter;
import com.sneezecast.domainlayer.aggregate.application.model.AggregateWeekResult;
import com.sneezecast.domainlayer.aggregate.domain.enums.AggregateLevel;
import com.sneezecast.domainlayer.aggregate.domain.enums.InsufficientReason;
import com.sneezecast.domainlayer.aggregate.domain.model.AggregateCalculation;
import com.sneezecast.domainlayer.aggregate.domain.model.AggregateCounts;
import com.sneezecast.domainlayer.aggregate.domain.model.AggregateJudgement;
import com.sneezecast.domainlayer.aggregate.domain.model.DistrictWeeklyAggregate;
import com.sneezecast.domainlayer.report.domain.model.ReportWeek;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 재계산 · 마감을 실제 트랜잭션 프록시와 스키마(H2 MySQL 모드)로 돌려 본다. 주는 인자로 넘기므로 시계와 무관하다 — 언제 어느 주를 닫는지는
 * {@code AggregateJobFacadeTest} 가 본다. 판정 값은 application.yml 기본값(최소 표본 100, 기준선 직전 8주 중 4주, 급변 50%, 3%p · 8%p)이다.
 */
class AggregateCommandProcessorTest extends AggregateH2TestSupport {

    private static final ReportWeek W41 = ReportWeek.parse("2026-W41");
    private static final ReportWeek W40 = ReportWeek.parse("2026-W40");
    private static final LocalDateTime EARLIER = LocalDateTime.of(2026, 10, 1, 9, 0);
    private static final LocalDateTime FINALIZED_AT = LocalDateTime.of(2026, 10, 5, 0, 10);

    @Autowired
    private AggregateCommandProcessor processor;

    @Autowired
    private DistrictWeeklyAggregateRepositoryAdapter aggregateAdapter;

    @BeforeEach
    void setUp() {
        clearReportsAndAggregates();
    }

    @Test
    @DisplayName("재계산은 원시 보고를 다시 세어 행정동마다 한 행으로 저장하고, 다시 돌려도 같은 행 · 같은 값이다 (멱등)")
    void recalculationIsIdempotent() {
        insertReports(W41, GARAK_1, NO_SYMPTOM, 1);
        insertReports(W41, GARAK_1, RESPIRATORY, 1, 1);
        insertReports(W41, GARAK_1, BOTH, 1);
        insertReports(W41, YEOKSAM_1, ENTERIC, 1);

        AggregateWeekResult first = processor.recalculateInProgress(W41);
        List<DistrictWeeklyAggregate> afterFirst = aggregateAdapter.findAllByIsoWeek(W41);
        AggregateWeekResult second = processor.recalculateInProgress(W41);
        List<DistrictWeeklyAggregate> afterSecond = aggregateAdapter.findAllByIsoWeek(W41);

        assertThat(first).extracting(AggregateWeekResult::reportedDistricts, AggregateWeekResult::inserted, AggregateWeekResult::updated)
            .containsExactly(2, 2, 0);
        assertThat(second).extracting(AggregateWeekResult::reportedDistricts, AggregateWeekResult::inserted, AggregateWeekResult::updated)
            .containsExactly(2, 0, 2);
        assertThat(afterSecond).usingRecursiveFieldByFieldElementComparatorIgnoringFields("calculatedAt", "updatedAt")
            .containsExactlyInAnyOrderElementsOf(afterFirst);
        DistrictWeeklyAggregate garak = row(W41, GARAK_1);
        assertThat(counts(garak)).isEqualTo(new AggregateCounts(3, 2, 2, 1, 1));
        assertThat(garak.level()).isEqualTo(AggregateLevel.INSUFFICIENT);
        assertThat(garak.insufficientReason()).isEqualTo(InsufficientReason.LOW_SAMPLE);
        assertThat(garak.ruleVersion()).isEqualTo("2026-10-08");
        assertThat(garak.finalized()).isFalse();
        assertThat(counts(row(W41, YEOKSAM_1))).isEqualTo(new AggregateCounts(1, 1, 0, 1, 0));
    }

    @Test
    @DisplayName("재계산 결과에 없는 기존 행은 0 과 LOW_SAMPLE 로 덮어쓴다 — 동을 바꾼 수정 · 파기로 0건이 된 동에 이전 수치가 남지 않는다")
    void rowMissingFromRecountIsZeroed() {
        // 지난 재계산이 남긴 행 — 그때는 표본이 충분했고 SLIGHT 였다.
        aggregateAdapter.insert(DistrictWeeklyAggregate.newAggregate(1L, new AggregateCalculation(YEOKSAM_1, W41, new AggregateCounts(120, 20, 15, 8, 3),
            AggregateJudgement.judged(AggregateLevel.SLIGHT, 400, 40), "2026-10-08", EARLIER)));
        insertReports(W41, GARAK_1, RESPIRATORY, 2);

        AggregateWeekResult result = processor.recalculateInProgress(W41);

        assertThat(result).extracting(AggregateWeekResult::inserted, AggregateWeekResult::updated).containsExactly(1, 1);
        DistrictWeeklyAggregate yeoksam = row(W41, YEOKSAM_1);
        assertThat(yeoksam.id()).isEqualTo(1L);
        assertZeroLowSample(yeoksam);
        assertThat(yeoksam.calculatedAt()).isAfter(EARLIER);

        // 파기(#155)는 그 보고자의 모든 주 행을 지운다 — 현재 주는 다음 재계산이 반영한다.
        jdbcTemplate.update("DELETE FROM weekly_report");
        processor.recalculateInProgress(W41);

        assertZeroLowSample(row(W41, GARAK_1));
    }

    @Test
    @DisplayName("마감은 마지막으로 다시 세고 finalized_at 을 채운다 — 마감된 주는 원시 보고가 바뀌어도 다시 세지 않고 마감 대상에도 다시 오르지 않는다")
    void closedWeekIsImmutable() {
        insertReports(W40, GARAK_1, RESPIRATORY, 2);

        AggregateWeekResult closed = processor.close(W40);

        assertThat(closed).extracting(AggregateWeekResult::inserted, AggregateWeekResult::finalized, AggregateWeekResult::alreadyFinalized)
            .containsExactly(1, 1, false);
        DistrictWeeklyAggregate finalized = row(W40, GARAK_1);
        assertThat(finalized.finalized()).isTrue();
        assertThat(counts(finalized)).isEqualTo(new AggregateCounts(2, 2, 2, 0, 0));

        // 마감 뒤 파기로 원시 보고가 사라지고, (지난 주 보고는 받지 않지만) 다른 동 보고가 생겨도
        jdbcTemplate.update("DELETE FROM weekly_report");
        insertReports(W40, YEOKSAM_1, NO_SYMPTOM, 3);

        assertThat(processor.close(W40)).extracting(AggregateWeekResult::inserted, AggregateWeekResult::updated, AggregateWeekResult::finalized,
            AggregateWeekResult::alreadyFinalized).containsExactly(0, 0, 0, true);
        assertThat(processor.recalculateInProgress(W40).alreadyFinalized()).isTrue();
        assertThat(aggregateAdapter.findAllByIsoWeek(W40)).singleElement().usingRecursiveComparison().isEqualTo(finalized);
        assertThat(processor.findWeeksToClose(W41)).isEmpty();
    }

    @Test
    @DisplayName("놓친 마감 — 미마감 행이 남은 주(기간 제한 없음)와, 보고는 있는데 집계 행이 없는 주(직전 lookback + 1 주)를 오래된 순으로 골라 모두 마감한다")
    void catchesUpMissedWeeks() {
        ReportWeek w30 = ReportWeek.parse("2026-W30");
        ReportWeek w31 = ReportWeek.parse("2026-W31");
        ReportWeek w32 = ReportWeek.parse("2026-W32");
        ReportWeek w36 = ReportWeek.parse("2026-W36");
        ReportWeek w38 = ReportWeek.parse("2026-W38");
        ReportWeek w39 = ReportWeek.parse("2026-W39");
        insertReports(w31, GARAK_1, NO_SYMPTOM, 1);
        processor.recalculateInProgress(w31);
        insertReports(w38, GARAK_1, NO_SYMPTOM, 1);
        processor.recalculateInProgress(w38);
        insertReports(w39, GARAK_1, NO_SYMPTOM, 1);
        processor.close(w39);
        // 재계산이 한 번도 돌지 않은 주 — 범위(W41 의 9주 앞 W32 부터) 밖인 W30 은 어느 판정에도 쓰이지 않아 고르지 않는다.
        insertReports(w30, GARAK_1, NO_SYMPTOM, 1);
        insertReports(w32, YEOKSAM_1, NO_SYMPTOM, 1);
        insertReports(w36, GARAK_1, RESPIRATORY, 1);
        // 마감 기준 주(현재 주)는 고르지 않는다.
        insertReports(W41, GARAK_1, NO_SYMPTOM, 1);

        List<ReportWeek> weeks = processor.findWeeksToClose(W41);

        assertThat(weeks).containsExactly(w31, w32, w36, w38);
        weeks.forEach(processor::close);
        assertThat(processor.findWeeksToClose(W41)).isEmpty();
        assertThat(aggregateAdapter.findAllByIsoWeekIn(weeks)).hasSize(4).allSatisfy(row -> assertThat(row.finalized()).isTrue());
        assertThat(counts(row(w36, GARAK_1))).isEqualTo(new AggregateCounts(1, 1, 1, 0, 0));
        assertThat(aggregateAdapter.findAllByIsoWeek(w30)).isEmpty();
        assertThat(row(W41, GARAK_1)).isNull();
    }

    @Test
    @DisplayName("집계 행이 없는 지난 5주를 오래된 순으로 주마다 닫으면, 뒤 주는 앞 주들을 마감된 기준선 · 전주로 받는다 — 마지막 주는 NO_BASELINE 이 아니고 급변은 UNSTABLE")
    void catchUpCloseFeedsLaterWeeksWithClosedBaseline() {
        // 가락1동: W-5 ~ W-2 는 증상 10%, W-1 은 20% (주마다 100명 — 표본 하한).
        for (int back = 5; back >= 2; back--) {
            insertReports(W41.minusWeeks(back), GARAK_1, RESPIRATORY, 10);
            insertReports(W41.minusWeeks(back), GARAK_1, NO_SYMPTOM, 90);
        }
        insertReports(W40, GARAK_1, RESPIRATORY, 20);
        insertReports(W40, GARAK_1, NO_SYMPTOM, 80);
        // 역삼1동: W-2 300명 → W-1 120명 (전주 대비 60% 감소 — 급변).
        insertReports(W41.minusWeeks(2), YEOKSAM_1, NO_SYMPTOM, 300);
        insertReports(W40, YEOKSAM_1, NO_SYMPTOM, 120);

        // Facade 와 같은 경로 — 고른 주를 오래된 순으로 주마다 새 트랜잭션에서 닫는다.
        List<ReportWeek> weeks = processor.findWeeksToClose(W41);
        assertThat(weeks).containsExactly(W41.minusWeeks(5), W41.minusWeeks(4), W41.minusWeeks(3), W41.minusWeeks(2), W40);
        weeks.forEach(processor::close);

        // 앞 주들은 기준선이 모자라 NO_BASELINE 이지만 마감됐고 표본이 충분해 뒤 주의 기준선이 된다.
        for (int back = 5; back >= 2; back--) {
            DistrictWeeklyAggregate earlier = row(W41.minusWeeks(back), GARAK_1);
            assertThat(earlier.finalized()).isTrue();
            assertThat(earlier.insufficientReason()).isEqualTo(InsufficientReason.NO_BASELINE);
        }
        DistrictWeeklyAggregate last = row(W40, GARAK_1);
        assertThat(last.finalized()).isTrue();
        assertThat(last.insufficientReason()).isNull();
        assertThat(last.level()).isEqualTo(AggregateLevel.HIGH);
        assertThat(last.baselineParticipantCount()).isEqualTo(400);
        assertThat(last.baselineSymptomaticCount()).isEqualTo(40);
        // 전주 행(같은 회차에서 먼저 닫힌 W-2)이 CLOSED 판정의 급변 비교에 쓰였다.
        DistrictWeeklyAggregate unstable = row(W40, YEOKSAM_1);
        assertThat(unstable.finalized()).isTrue();
        assertThat(unstable.insufficientReason()).isEqualTo(InsufficientReason.UNSTABLE);
        assertThat(processor.findWeeksToClose(W41)).isEmpty();
    }

    @Test
    @DisplayName("현재 주 재계산은 IN_PROGRESS 라 참여 급변을 보지 않고, 마감은 CLOSED 라 전주 대비 급변을 UNSTABLE 로 남긴다")
    void unstableOnlyWhenClosed() {
        // 전주 300명 — 급변 비교에는 행만 있으면 된다(마감 여부 무관).
        insertHistory(1L, W40, 300, 0);
        insertReports(W41, GARAK_1, NO_SYMPTOM, 120);

        processor.recalculateInProgress(W41);
        assertThat(row(W41, GARAK_1).insufficientReason()).isEqualTo(InsufficientReason.NO_BASELINE);

        processor.close(W41);
        DistrictWeeklyAggregate closed = row(W41, GARAK_1);
        assertThat(closed.insufficientReason()).isEqualTo(InsufficientReason.UNSTABLE);
        assertThat(closed.finalized()).isTrue();
    }

    @Test
    @DisplayName("직전 마감 주들을 기준선으로 읽어 단계를 정하고 기준선 두 수를 남긴다 — 기준선 10% 에서 20% 면 많이 늘었어요")
    void judgesAgainstFinalizedBaseline() {
        for (int back = 1; back <= 4; back++) {
            insertHistory(back, W41.minusWeeks(back), 200, 20);
        }
        jdbcTemplate.update("UPDATE district_weekly_aggregate SET finalized_at = ?", Timestamp.valueOf(FINALIZED_AT));
        insertReports(W41, GARAK_1, RESPIRATORY, 20);
        insertReports(W41, GARAK_1, NO_SYMPTOM, 80);

        processor.recalculateInProgress(W41);

        DistrictWeeklyAggregate judged = row(W41, GARAK_1);
        assertThat(judged.level()).isEqualTo(AggregateLevel.HIGH);
        assertThat(judged.baselineParticipantCount()).isEqualTo(800);
        assertThat(judged.baselineSymptomaticCount()).isEqualTo(80);
    }

    // 가락1동 지난 주 행. 판정은 기준선 자격과 무관한 NO_BASELINE 으로 둔다.
    private void insertHistory(long id, ReportWeek week, int participants, int symptomatic) {
        aggregateAdapter.insert(DistrictWeeklyAggregate.newAggregate(id, new AggregateCalculation(GARAK_1, week,
            new AggregateCounts(participants, symptomatic, symptomatic, 0, 0), AggregateJudgement.insufficient(InsufficientReason.NO_BASELINE),
            "2026-10-08", EARLIER)));
    }

    private DistrictWeeklyAggregate row(ReportWeek week, String districtCode) {
        return aggregateAdapter.findAllByIsoWeek(week).stream().filter(row -> row.districtCode().equals(districtCode)).findFirst().orElse(null);
    }

    private static AggregateCounts counts(DistrictWeeklyAggregate row) {
        return new AggregateCounts(row.participantCount(), row.symptomaticCount(), row.respiratoryCount(), row.entericCount(), row.revisedReportCount());
    }

    private static void assertZeroLowSample(DistrictWeeklyAggregate row) {
        assertThat(counts(row)).isEqualTo(AggregateCounts.ZERO);
        assertThat(row.level()).isEqualTo(AggregateLevel.INSUFFICIENT);
        assertThat(row.insufficientReason()).isEqualTo(InsufficientReason.LOW_SAMPLE);
        assertThat(row.baselineParticipantCount()).isNull();
        assertThat(row.baselineSymptomaticCount()).isNull();
    }
}
