package com.sneezecast.domainlayer.aggregate;

import com.sneezecast.SurveillanceH2TestSupport;
import com.sneezecast.domainlayer.report.domain.model.ReportWeek;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 집계 H2 테스트 공통 헬퍼. 컨텍스트 구성은 {@link SurveillanceH2TestSupport} 를 그대로 쓴다.
 *
 * <p>원시 보고는 JDBC 로 넣는다 — 표본 하한(100명)을 넘기려면 수백 행이 필요하고, 보고 API 는 현재 주만 받아 지난 주 보고를 만들 수 없다.
 * 보고자 키는 행마다 새로 만든다 (같은 사람의 같은 주는 한 행이다).
 */
public abstract class AggregateH2TestSupport extends SurveillanceH2TestSupport {

    protected static final String GARAK_1 = "11240660";
    protected static final String YEOKSAM_1 = "11230510";
    protected static final int NO_SYMPTOM = 0;
    protected static final int RESPIRATORY = 1;
    protected static final int ENTERIC = 2;
    protected static final int BOTH = 3;

    private static final AtomicLong SEQUENCE = new AtomicLong(1_000_000L);

    protected void clearReportsAndAggregates() {
        jdbcTemplate.update("DELETE FROM weekly_report");
        jdbcTemplate.update("DELETE FROM district_weekly_aggregate");
    }

    /** 새 보고자 {@code count} 명이 같은 주 · 행정동 · 증상군으로 보고한 행. {@code revisionCount} 가 0 보다 크면 수정된 보고다. */
    protected void insertReports(ReportWeek week, String districtCode, int symptomMask, int count, int revisionCount) {
        List<Object[]> rows = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            long id = SEQUENCE.incrementAndGet();
            rows.add(new Object[] {id, reporterKey(id), week.value(), districtCode, symptomMask, revisionCount});
        }
        jdbcTemplate.batchUpdate("""
            INSERT INTO weekly_report (id, reporter_key, iso_week, district_code, symptom_mask, revision_count, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)""", rows);
    }

    protected void insertReports(ReportWeek week, String districtCode, int symptomMask, int count) {
        insertReports(week, districtCode, symptomMask, count, 0);
    }

    /** 64자 소문자 hex 꼴의 가명 키. */
    protected static String reporterKey(long seed) {
        return String.format(Locale.ROOT, "%064x", seed);
    }
}
