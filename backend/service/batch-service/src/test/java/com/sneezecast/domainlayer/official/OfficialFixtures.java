package com.sneezecast.domainlayer.official;

import com.sneezecast.domainlayer.official.domain.enums.IngestChannel;
import com.sneezecast.domainlayer.official.domain.enums.OfficialAgeGroup;
import com.sneezecast.domainlayer.official.domain.enums.OfficialMetric;
import com.sneezecast.domainlayer.official.domain.enums.OfficialPeriodType;
import com.sneezecast.domainlayer.official.domain.enums.OfficialProgram;
import com.sneezecast.domainlayer.official.domain.enums.OfficialRegionLevel;
import com.sneezecast.domainlayer.official.domain.enums.OfficialSource;
import com.sneezecast.domainlayer.official.domain.model.KdcaWeek;
import com.sneezecast.domainlayer.official.domain.model.OfficialRecord;
import com.sneezecast.domainlayer.official.domain.model.OfficialSnapshotDraft;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/** official 테스트 공용 — 표본감시 급성호흡기(ARI) 전국 주별 행과 그 수집 기록, H2 스키마. */
public final class OfficialFixtures {

    public static final LocalDateTime RUN_STARTED_AT = LocalDateTime.of(2026, 10, 2, 6, 0);
    public static final String SHA256 = "a".repeat(64);

    private OfficialFixtures() {
    }

    /** 2026년 {@code week} 주 전국 ARI 신고 수. 기간은 질병관리청 주차 규칙으로 채운다. */
    public static OfficialRecord ariWeek(String diseaseKey, int week, String value) {
        KdcaWeek kdcaWeek = new KdcaWeek(2026, week);
        return new OfficialRecord(OfficialSource.KDCA_SENTINEL, OfficialProgram.ARI, diseaseKey, "병원체 " + diseaseKey, "바이러스",
            OfficialMetric.CASE_COUNT, OfficialAgeGroup.ALL, OfficialRegionLevel.NATION, OfficialRecord.NATION_REGION_CODE, "전국",
            OfficialPeriodType.WEEK, 2026, week, kdcaWeek.start(), kdcaWeek.end(), value == null ? null : new BigDecimal(value));
    }

    public static OfficialSnapshotDraft ariDraft(String requestKey, int rowCount) {
        return new OfficialSnapshotDraft(OfficialSource.KDCA_SENTINEL, OfficialProgram.ARI, requestKey, IngestChannel.PORTAL_JSON, SHA256, 1024,
            rowCount, RUN_STARTED_AT);
    }

    public static DriverManagerDataSource h2DataSource(String prefix) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
            "jdbc:h2:mem:" + prefix + "-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("official/official-schema.sql")).execute(dataSource);
        return dataSource;
    }
}
