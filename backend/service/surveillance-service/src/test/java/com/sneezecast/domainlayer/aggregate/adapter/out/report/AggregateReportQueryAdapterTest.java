package com.sneezecast.domainlayer.aggregate.adapter.out.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sneezecast.domainlayer.aggregate.AggregateH2TestSupport;
import com.sneezecast.domainlayer.aggregate.domain.model.AggregateCounts;
import com.sneezecast.domainlayer.report.adapter.out.persistence.WeeklyReportRepositoryAdapter;
import com.sneezecast.domainlayer.report.domain.model.ReportWeek;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 원시 보고 집계 조회를 실제 스키마(H2 MySQL 모드)에 돌려 본다 — report 의 {@code GROUP BY} JPQL 과 증상군 비트 접기를 함께 확인한다.
 */
class AggregateReportQueryAdapterTest extends AggregateH2TestSupport {

    private static final ReportWeek W41 = ReportWeek.parse("2026-W41");
    private static final ReportWeek W42 = ReportWeek.parse("2026-W42");

    @Autowired
    private AggregateReportQueryAdapter adapter;

    @Autowired
    private WeeklyReportRepositoryAdapter weeklyReportRepositoryAdapter;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        clearReportsAndAggregates();
    }

    @Test
    @DisplayName("행정동별로 참여 · 증상 · 증상군 · 수정 보고를 센다 — 증상 없음도 참여이고, 둘 다인 보고는 호흡기 · 장관 양쪽에 센다")
    void countsByDistrict() {
        insertReports(W41, GARAK_1, NO_SYMPTOM, 1);
        insertReports(W41, GARAK_1, NO_SYMPTOM, 1, 2);
        insertReports(W41, GARAK_1, RESPIRATORY, 1);
        insertReports(W41, GARAK_1, ENTERIC, 1);
        insertReports(W41, GARAK_1, BOTH, 1, 1);
        insertReports(W41, YEOKSAM_1, NO_SYMPTOM, 1);
        // 다른 주는 세지 않는다.
        insertReports(W42, GARAK_1, RESPIRATORY, 3);

        Map<String, AggregateCounts> counts = adapter.countByDistrict(W41);

        assertThat(counts).containsOnlyKeys(GARAK_1, YEOKSAM_1);
        assertThat(counts.get(GARAK_1)).isEqualTo(new AggregateCounts(5, 3, 2, 2, 2));
        assertThat(counts.get(YEOKSAM_1)).isEqualTo(new AggregateCounts(1, 0, 0, 0, 0));
        assertThat(adapter.countByDistrict(ReportWeek.parse("2026-W40"))).isEmpty();
    }

    @Test
    @DisplayName("같은 사람이 같은 주에 고친 보고는 한 번만 세고, 마지막 행정동 · 증상군으로 센다")
    void revisedReportCountsOnceWithLatestValues() {
        insertReports(W41, GARAK_1, RESPIRATORY, 1);
        String reporterKey = jdbcTemplate.queryForObject("SELECT reporter_key FROM weekly_report", String.class);

        new TransactionTemplate(transactionManager).executeWithoutResult(
            status -> weeklyReportRepositoryAdapter.updateCurrent(reporterKey, W41, YEOKSAM_1, Set.of()));

        assertThat(adapter.countByDistrict(W41)).containsExactly(Map.entry(YEOKSAM_1, new AggregateCounts(1, 0, 0, 0, 1)));
    }

    @Test
    @DisplayName("모르는 증상군 비트가 저장돼 있으면 조용히 버리지 않고 실패한다")
    void unknownSymptomBitFails() {
        insertReports(W41, GARAK_1, 4, 1);

        assertThatThrownBy(() -> adapter.countByDistrict(W41)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("mask=4");
    }

    @Test
    @DisplayName("보고가 있는 주를 [from, to) 범위에서 오래된 순으로 한 번씩 준다 — 연말 W52 · W53 이 다음 해 W01 보다 앞이다")
    void findReportedWeeks() {
        ReportWeek w52 = ReportWeek.parse("2026-W52");
        ReportWeek w53 = ReportWeek.parse("2026-W53");
        ReportWeek nextW01 = ReportWeek.parse("2027-W01");
        ReportWeek nextW02 = ReportWeek.parse("2027-W02");
        insertReports(nextW01, GARAK_1, NO_SYMPTOM, 2);
        insertReports(w53, YEOKSAM_1, NO_SYMPTOM, 1);
        insertReports(w52, GARAK_1, NO_SYMPTOM, 1);
        insertReports(nextW02, GARAK_1, NO_SYMPTOM, 1);

        assertThat(adapter.findReportedWeeks(w53, nextW02)).containsExactly(w53, nextW01);
        assertThat(adapter.findReportedWeeks(ReportWeek.parse("2026-W40"), w52)).isEmpty();
    }
}
