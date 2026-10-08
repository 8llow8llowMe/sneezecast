package com.sneezecast.domainlayer.aggregate.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sneezecast.SurveillanceH2TestSupport;
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
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 집계 저장 어댑터를 실제 스키마(H2 MySQL 모드)에 돌려 본다 — 갱신 · 마감 JPQL 은 컴파일로 검증되지 않는다. 테스트 메서드에는 트랜잭션을 걸지
 * 않는다. 운영처럼 어댑터 호출마다 커밋돼야 제약 위반 · 트랜잭션 필수 규칙이 그대로 드러난다.
 */
class DistrictWeeklyAggregateRepositoryAdapterTest extends SurveillanceH2TestSupport {

    private static final String GARAK_1 = "11240660";
    private static final String YEOKSAM_1 = "11230510";
    private static final ReportWeek W52 = ReportWeek.parse("2026-W52");
    private static final ReportWeek W53 = ReportWeek.parse("2026-W53");
    private static final ReportWeek NEXT_W01 = ReportWeek.parse("2027-W01");
    private static final LocalDateTime CALCULATED_AT = LocalDateTime.of(2026, 12, 30, 9, 0);
    private static final LocalDateTime RECALCULATED_AT = LocalDateTime.of(2026, 12, 31, 9, 0);
    private static final LocalDateTime FINALIZED_AT = LocalDateTime.of(2027, 1, 4, 0, 10);
    private static final LocalDateTime OLD_UPDATED_AT = LocalDateTime.of(2000, 1, 1, 0, 0);
    private static final AggregateCounts COUNTS = new AggregateCounts(120, 20, 15, 8, 3);
    private static final AggregateJudgement SLIGHT = AggregateJudgement.judged(AggregateLevel.SLIGHT, 400, 40);
    private static final AggregateJudgement LOW_SAMPLE = AggregateJudgement.insufficient(InsufficientReason.LOW_SAMPLE);

    @Autowired
    private DistrictWeeklyAggregateRepositoryAdapter adapter;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM district_weekly_aggregate");
        tx = new TransactionTemplate(transactionManager);
    }

    @Test
    @DisplayName("새 행을 저장하면 감사 시각이 채워지고, 수치 · 판정 · 기준선이 그대로 다시 읽힌다")
    void insertAndFindByWeek() {
        DistrictWeeklyAggregate saved = adapter.insert(DistrictWeeklyAggregate.newAggregate(1L, calculation(GARAK_1, W53, COUNTS, SLIGHT)));

        assertThat(saved.createdAt()).isNotNull();
        assertThat(saved.updatedAt()).isNotNull();
        DistrictWeeklyAggregate found = adapter.findAllByIsoWeek(W53).get(0);
        assertThat(found.id()).isEqualTo(1L);
        assertThat(found.districtCode()).isEqualTo(GARAK_1);
        assertThat(found.isoWeek()).isEqualTo(W53);
        assertThat(found.participantCount()).isEqualTo(120);
        assertThat(found.symptomaticCount()).isEqualTo(20);
        assertThat(found.respiratoryCount()).isEqualTo(15);
        assertThat(found.entericCount()).isEqualTo(8);
        assertThat(found.revisedReportCount()).isEqualTo(3);
        assertThat(found.level()).isEqualTo(AggregateLevel.SLIGHT);
        assertThat(found.insufficientReason()).isNull();
        assertThat(found.baselineParticipantCount()).isEqualTo(400);
        assertThat(found.baselineSymptomaticCount()).isEqualTo(40);
        assertThat(found.ruleVersion()).isEqualTo("2026-10-08");
        assertThat(found.calculatedAt()).isEqualTo(CALCULATED_AT);
        assertThat(found.finalized()).isFalse();
        assertThat(row(GARAK_1, W53)).containsEntry("LEVEL", "SLIGHT").containsEntry("ISO_WEEK", "2026-W53");
    }

    @Test
    @DisplayName("자료 부족 행은 이유가 문자열로 저장되고 기준선은 null 이다")
    void insufficientRowStoresReasonWithoutBaseline() {
        adapter.insert(DistrictWeeklyAggregate.newAggregate(1L, calculation(GARAK_1, W53, new AggregateCounts(0, 0, 0, 0, 0), LOW_SAMPLE)));

        assertThat(row(GARAK_1, W53))
            .containsEntry("LEVEL", "INSUFFICIENT")
            .containsEntry("INSUFFICIENT_REASON", "LOW_SAMPLE")
            .containsEntry("BASELINE_PARTICIPANT_COUNT", null)
            .containsEntry("BASELINE_SYMPTOMATIC_COUNT", null);
        assertThat(adapter.findAllByIsoWeek(W53).get(0).insufficientReason()).isEqualTo(InsufficientReason.LOW_SAMPLE);
    }

    @Test
    @DisplayName("같은 행정동 · 주 두 번째 insert 는 제약 위반 그대로 실패하고, 먼저 있던 행은 덮어쓰지 않는다")
    void secondInsertForSameCellFails() {
        adapter.insert(DistrictWeeklyAggregate.newAggregate(1L, calculation(GARAK_1, W53, COUNTS, SLIGHT)));

        assertThatThrownBy(() -> adapter.insert(DistrictWeeklyAggregate.newAggregate(2L, calculation(GARAK_1, W53, COUNTS, LOW_SAMPLE))))
            .isInstanceOf(DataIntegrityViolationException.class);
        // PK 가 겹쳐도 merge 로 덮어쓰지 않는다 (Persistable).
        assertThatThrownBy(() -> adapter.insert(DistrictWeeklyAggregate.newAggregate(1L, calculation(YEOKSAM_1, W53, COUNTS, LOW_SAMPLE))))
            .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(adapter.findAllByIsoWeek(W53)).singleElement().satisfies(found -> {
            assertThat(found.id()).isEqualTo(1L);
            assertThat(found.level()).isEqualTo(AggregateLevel.SLIGHT);
        });
    }

    @Test
    @DisplayName("마감된 행은 새로 저장하지 않는다")
    void insertRejectsFinalizedRow() {
        DistrictWeeklyAggregate finalized = DistrictWeeklyAggregate.builder().id(1L).districtCode(GARAK_1).isoWeek(W53).participantCount(120)
            .symptomaticCount(20).respiratoryCount(15).entericCount(8).revisedReportCount(3).level(AggregateLevel.SLIGHT).baselineParticipantCount(400)
            .baselineSymptomaticCount(40).ruleVersion("2026-10-08").calculatedAt(CALCULATED_AT).finalizedAt(FINALIZED_AT).build();

        assertThatThrownBy(() -> adapter.insert(finalized)).isInstanceOf(IllegalArgumentException.class);
        assertThat(adapter.findAllByIsoWeek(W53)).isEmpty();
    }

    @Test
    @DisplayName("여러 주를 한 번에 읽는다 — 연말 W52 · W53 과 다음 해 W01, 빈 목록이면 빈 결과")
    void findAllByIsoWeekIn() {
        adapter.insert(DistrictWeeklyAggregate.newAggregate(1L, calculation(GARAK_1, W52, COUNTS, SLIGHT)));
        adapter.insert(DistrictWeeklyAggregate.newAggregate(2L, calculation(GARAK_1, W53, COUNTS, SLIGHT)));
        adapter.insert(DistrictWeeklyAggregate.newAggregate(3L, calculation(YEOKSAM_1, W53, COUNTS, SLIGHT)));
        adapter.insert(DistrictWeeklyAggregate.newAggregate(4L, calculation(GARAK_1, NEXT_W01, COUNTS, SLIGHT)));

        assertThat(adapter.findAllByIsoWeekIn(List.of(W52, W53, W53))).extracting(DistrictWeeklyAggregate::id).containsExactlyInAnyOrder(1L, 2L, 3L);
        assertThat(adapter.findAllByIsoWeek(W53)).extracting(DistrictWeeklyAggregate::id).containsExactlyInAnyOrder(2L, 3L);
        assertThat(adapter.findAllByIsoWeekIn(List.of())).isEmpty();
    }

    @Test
    @DisplayName("재계산은 수치 · 판정 · 기준선 · 규칙 버전 · 계산 시각과 updated_at 을 다시 쓴다 — 판정에서 자료 부족으로 바뀌면 기준선은 null 이 된다")
    void updateCalculationRewritesRow() {
        adapter.insert(DistrictWeeklyAggregate.newAggregate(1L, calculation(GARAK_1, W53, COUNTS, SLIGHT)));
        backdateUpdatedAt();
        AggregateCalculation recalculated = new AggregateCalculation(GARAK_1, W53, new AggregateCounts(90, 10, 9, 2, 1), LOW_SAMPLE, "2026-11-01",
            RECALCULATED_AT);

        assertThat(update(recalculated)).isEqualTo(1);

        DistrictWeeklyAggregate found = adapter.findAllByIsoWeek(W53).get(0);
        assertThat(found.participantCount()).isEqualTo(90);
        assertThat(found.symptomaticCount()).isEqualTo(10);
        assertThat(found.respiratoryCount()).isEqualTo(9);
        assertThat(found.entericCount()).isEqualTo(2);
        assertThat(found.revisedReportCount()).isEqualTo(1);
        assertThat(found.level()).isEqualTo(AggregateLevel.INSUFFICIENT);
        assertThat(found.insufficientReason()).isEqualTo(InsufficientReason.LOW_SAMPLE);
        assertThat(found.baselineParticipantCount()).isNull();
        assertThat(found.baselineSymptomaticCount()).isNull();
        assertThat(found.ruleVersion()).isEqualTo("2026-11-01");
        assertThat(found.calculatedAt()).isEqualTo(RECALCULATED_AT);
        assertThat(found.updatedAt()).isAfter(OLD_UPDATED_AT);

        // 다시 판정되면 이유가 지워지고 기준선이 채워진다.
        assertThat(update(calculation(GARAK_1, W53, COUNTS, SLIGHT))).isEqualTo(1);
        assertThat(row(GARAK_1, W53)).containsEntry("INSUFFICIENT_REASON", null).containsEntry("BASELINE_PARTICIPANT_COUNT", 400);
    }

    @Test
    @DisplayName("재계산은 다른 행정동 · 다른 주를 건드리지 않고, 없는 칸이면 0 이다")
    void updateCalculationIsScopedToCell() {
        adapter.insert(DistrictWeeklyAggregate.newAggregate(1L, calculation(GARAK_1, W52, COUNTS, SLIGHT)));
        adapter.insert(DistrictWeeklyAggregate.newAggregate(2L, calculation(YEOKSAM_1, W53, COUNTS, SLIGHT)));
        backdateUpdatedAt();

        assertThat(update(calculation(GARAK_1, W53, COUNTS, LOW_SAMPLE))).isZero();

        for (Map<String, Object> untouched : List.of(row(GARAK_1, W52), row(YEOKSAM_1, W53))) {
            assertThat(untouched).containsEntry("LEVEL", "SLIGHT");
            assertThat(((Timestamp) untouched.get("UPDATED_AT")).toLocalDateTime()).isEqualTo(OLD_UPDATED_AT);
        }
    }

    @Test
    @DisplayName("마감된 행은 재계산하지 않는다 — 0 을 돌려주고 값 · updated_at 이 그대로다")
    void updateCalculationSkipsFinalizedRow() {
        adapter.insert(DistrictWeeklyAggregate.newAggregate(1L, calculation(GARAK_1, W53, COUNTS, SLIGHT)));
        finalizeWeek(W53, FINALIZED_AT);
        backdateUpdatedAt();

        assertThat(update(calculation(GARAK_1, W53, new AggregateCounts(0, 0, 0, 0, 0), LOW_SAMPLE))).isZero();

        DistrictWeeklyAggregate found = adapter.findAllByIsoWeek(W53).get(0);
        assertThat(found.participantCount()).isEqualTo(120);
        assertThat(found.level()).isEqualTo(AggregateLevel.SLIGHT);
        assertThat(found.finalizedAt()).isEqualTo(FINALIZED_AT);
        assertThat(found.updatedAt()).isEqualTo(OLD_UPDATED_AT);
    }

    @Test
    @DisplayName("마감은 그 주의 마감 안 된 행만 채우고 건수를 준다 — 다시 불러도 마감 시각이 바뀌지 않는다")
    void finalizeWeekIsIdempotent() {
        adapter.insert(DistrictWeeklyAggregate.newAggregate(1L, calculation(GARAK_1, W53, COUNTS, SLIGHT)));
        adapter.insert(DistrictWeeklyAggregate.newAggregate(2L, calculation(YEOKSAM_1, W53, COUNTS, LOW_SAMPLE)));
        adapter.insert(DistrictWeeklyAggregate.newAggregate(3L, calculation(GARAK_1, NEXT_W01, COUNTS, SLIGHT)));

        assertThat(finalizeWeek(W53, FINALIZED_AT)).isEqualTo(2);
        assertThat(finalizeWeek(W53, FINALIZED_AT.plusDays(1))).isZero();

        assertThat(adapter.findAllByIsoWeek(W53)).allSatisfy(found -> assertThat(found.finalizedAt()).isEqualTo(FINALIZED_AT));
        assertThat(adapter.findAllByIsoWeek(NEXT_W01)).singleElement().satisfies(found -> assertThat(found.finalized()).isFalse());
    }

    @Test
    @DisplayName("마감 안 된 지난 주를 오래된 순으로 한 번씩 준다 — 현재 주 · 마감된 주는 빼고, 연말 W53 이 다음 해 W01 보다 앞이다")
    void findUnfinalizedWeeksBefore() {
        adapter.insert(DistrictWeeklyAggregate.newAggregate(1L, calculation(GARAK_1, W52, COUNTS, SLIGHT)));
        adapter.insert(DistrictWeeklyAggregate.newAggregate(2L, calculation(GARAK_1, W53, COUNTS, SLIGHT)));
        adapter.insert(DistrictWeeklyAggregate.newAggregate(3L, calculation(YEOKSAM_1, W53, COUNTS, SLIGHT)));
        adapter.insert(DistrictWeeklyAggregate.newAggregate(4L, calculation(GARAK_1, NEXT_W01, COUNTS, SLIGHT)));
        adapter.insert(DistrictWeeklyAggregate.newAggregate(5L, calculation(GARAK_1, ReportWeek.parse("2027-W02"), COUNTS, SLIGHT)));
        finalizeWeek(W52, FINALIZED_AT);

        assertThat(adapter.findUnfinalizedWeeksBefore(ReportWeek.parse("2027-W02"))).containsExactly(W53, NEXT_W01);
        assertThat(adapter.findUnfinalizedWeeksBefore(W52)).isEmpty();
    }

    @Test
    @DisplayName("트랜잭션 밖에서 재계산 · 마감을 부르면 거부한다")
    void updateAndFinalizeRequireTransaction() {
        adapter.insert(DistrictWeeklyAggregate.newAggregate(1L, calculation(GARAK_1, W53, COUNTS, SLIGHT)));

        assertThatThrownBy(() -> adapter.updateCalculation(calculation(GARAK_1, W53, COUNTS, LOW_SAMPLE)))
            .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> adapter.finalizeWeek(W53, FINALIZED_AT)).isInstanceOf(IllegalTransactionStateException.class);
        assertThat(row(GARAK_1, W53)).containsEntry("LEVEL", "SLIGHT").containsEntry("FINALIZED_AT", null);
    }

    private static AggregateCalculation calculation(String districtCode, ReportWeek isoWeek, AggregateCounts counts, AggregateJudgement judgement) {
        return new AggregateCalculation(districtCode, isoWeek, counts, judgement, "2026-10-08", CALCULATED_AT);
    }

    private int update(AggregateCalculation calculation) {
        Integer updated = tx.execute(status -> adapter.updateCalculation(calculation));
        return updated == null ? 0 : updated;
    }

    private int finalizeWeek(ReportWeek isoWeek, LocalDateTime finalizedAt) {
        Integer finalized = tx.execute(status -> adapter.finalizeWeek(isoWeek, finalizedAt));
        return finalized == null ? 0 : finalized;
    }

    private void backdateUpdatedAt() {
        jdbcTemplate.update("UPDATE district_weekly_aggregate SET updated_at = ?", Timestamp.valueOf(OLD_UPDATED_AT));
    }

    private Map<String, Object> row(String districtCode, ReportWeek isoWeek) {
        return jdbcTemplate.queryForMap("SELECT * FROM district_weekly_aggregate WHERE district_code = ? AND iso_week = ?", districtCode, isoWeek.value());
    }
}
