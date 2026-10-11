package com.sneezecast.domainlayer.official;

import com.sneezecast.SurveillanceH2TestSupport;
import com.sneezecast.domainlayer.official.domain.enums.OfficialAgeGroup;
import com.sneezecast.domainlayer.official.domain.enums.OfficialMetric;
import com.sneezecast.domainlayer.official.domain.enums.OfficialPeriodType;
import com.sneezecast.domainlayer.official.domain.enums.OfficialProgram;
import com.sneezecast.domainlayer.official.domain.enums.OfficialRegionLevel;
import com.sneezecast.domainlayer.official.domain.enums.OfficialSource;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 공식 정보 H2 테스트 공통 헬퍼. 컨텍스트 구성은 {@link SurveillanceH2TestSupport} 를 그대로 쓴다.
 *
 * <p>행은 JDBC 로 넣는다 — 실제로도 batch-service 가 JDBC upsert 로 쓰고, surveillance 리포지토리에는 저장 메서드가 없다. 주 단위 행의 연도 · 주차는
 * batch 의 {@code KdcaWeek} 와 같은 규칙(일요일 시작, 1월 1일이 든 주가 1주)으로 시작일에서 거꾸로 구한다.
 */
public abstract class OfficialH2TestSupport extends SurveillanceH2TestSupport {

    protected static final LocalDateTime SYNCED_AT = LocalDateTime.of(2026, 9, 25, 6, 0, 4);

    private final AtomicLong nextId = new AtomicLong(1);

    protected void clearOfficial() {
        jdbcTemplate.update("DELETE FROM official_surveillance");
    }

    /** 전국 · 연령 전체 · 신고 수 주 단위 행. 원천은 프로그램에서 정한다. */
    protected void insertWeekly(OfficialProgram program, String diseaseKey, LocalDate periodStart, BigDecimal value) {
        insertWeekly(program, diseaseKey, OfficialMetric.CASE_COUNT, OfficialAgeGroup.ALL, periodStart, value, SYNCED_AT);
    }

    protected void insertWeekly(OfficialProgram program, String diseaseKey, OfficialMetric metric, OfficialAgeGroup ageGroup, LocalDate periodStart,
        BigDecimal value, LocalDateTime syncedAt) {
        LocalDate periodEnd = periodStart.plusDays(6);
        int year = periodEnd.getYear();
        LocalDate firstWeekStart = LocalDate.of(year, 1, 1).with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY));
        int week = (int) ChronoUnit.WEEKS.between(firstWeekStart, periodStart) + 1;
        insert(program, diseaseKey, metric, ageGroup, OfficialRegionLevel.NATION, "00", "전국", OfficialPeriodType.WEEK, year, week, periodStart, periodEnd,
            value, syncedAt);
    }

    /** 전수신고 시도 연 단위 행 (공식 정보 조회가 읽지 않는 모양). */
    protected void insertSidoYearly(String diseaseKey, String sidoCode, String sidoName, int year, BigDecimal value) {
        insert(OfficialProgram.NOTIFIABLE, diseaseKey, OfficialMetric.CASE_COUNT, OfficialAgeGroup.ALL, OfficialRegionLevel.SIDO, sidoCode, sidoName,
            OfficialPeriodType.YEAR, year, 0, LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31), value, SYNCED_AT);
    }

    private void insert(OfficialProgram program, String diseaseKey, OfficialMetric metric, OfficialAgeGroup ageGroup, OfficialRegionLevel regionLevel,
        String regionCode, String regionName, OfficialPeriodType periodType, int year, int week, LocalDate periodStart, LocalDate periodEnd,
        BigDecimal value, LocalDateTime syncedAt) {
        OfficialSource source = program == OfficialProgram.NOTIFIABLE ? OfficialSource.KDCA_NOTIFIABLE : OfficialSource.KDCA_SENTINEL;
        jdbcTemplate.update("""
                INSERT INTO official_surveillance (id, source, program, disease_key, disease_name, disease_group, metric, age_group,
                                                   region_level, region_code, region_name, period_type, period_year, period_week,
                                                   period_start, period_end, metric_value, source_snapshot_id, synced_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)""",
            nextId.getAndIncrement(), source.name(), program.name(), diseaseKey, diseaseKey + " 이름", diseaseGroup(program), metric.name(), ageGroup.name(),
            regionLevel.name(), regionCode, regionName, periodType.name(), year, week, periodStart, periodEnd, value, Timestamp.valueOf(syncedAt));
    }

    /** 인플루엔자 분율은 원천 분류가 없다 (batch {@code SentinelRecordProcessor}). */
    private static String diseaseGroup(OfficialProgram program) {
        return switch (program) {
            case NOTIFIABLE -> "제2급";
            case INFLUENZA_ILI -> null;
            case ARI, ENTERIC -> "바이러스";
        };
    }
}
