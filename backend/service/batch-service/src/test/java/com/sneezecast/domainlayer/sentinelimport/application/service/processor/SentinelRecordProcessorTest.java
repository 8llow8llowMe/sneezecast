package com.sneezecast.domainlayer.sentinelimport.application.service.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sneezecast.domainlayer.official.domain.enums.OfficialAgeGroup;
import com.sneezecast.domainlayer.official.domain.enums.OfficialMetric;
import com.sneezecast.domainlayer.official.domain.enums.OfficialPeriodType;
import com.sneezecast.domainlayer.official.domain.enums.OfficialProgram;
import com.sneezecast.domainlayer.official.domain.enums.OfficialRegionLevel;
import com.sneezecast.domainlayer.official.domain.enums.OfficialSource;
import com.sneezecast.domainlayer.official.domain.model.KdcaWeek;
import com.sneezecast.domainlayer.official.domain.model.OfficialRecord;
import com.sneezecast.domainlayer.sentinelimport.application.exception.SentinelImportErrorCode;
import com.sneezecast.domainlayer.sentinelimport.application.exception.SentinelImportException;
import com.sneezecast.domainlayer.sentinelimport.domain.model.SentinelIliRow;
import com.sneezecast.domainlayer.sentinelimport.domain.model.SentinelPathogenRow;
import com.sneezecast.domainlayer.sentinelimport.domain.model.SentinelProgram;
import com.sneezecast.domainlayer.sentinelimport.domain.model.SentinelRequest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class SentinelRecordProcessorTest {

    private static final SentinelRequest ENTERIC = SentinelRequest.weeks(SentinelProgram.ENTERIC, new KdcaWeek(2026, 33), new KdcaWeek(2026, 40));

    private final SentinelRecordProcessor processor = new SentinelRecordProcessor();

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = SentinelProgram.class, names = {"ARI", "ENTERIC"})
    @DisplayName("병원체 행은 표본감시 · 요청의 프로그램 · 신고 수 · 연령 ALL · 전국(00/전국) · WEEK 이고, 병원체 코드 · 이름 · 분류는 원천 그대로다")
    void convertsPathogenRow(SentinelProgram program) {
        SentinelRequest request = SentinelRequest.weeks(program, new KdcaWeek(2026, 33), new KdcaWeek(2026, 40));
        SentinelPathogenRow row = new SentinelPathogenRow(2026, 38, "HRV", "리노바이러스", "바이러스", new BigDecimal("42"));

        List<OfficialRecord> records = processor.pathogenRecords(request, List.of(row));

        assertThat(records).containsExactly(new OfficialRecord(OfficialSource.KDCA_SENTINEL, program.getOfficialProgram(), "HRV", "리노바이러스",
            "바이러스", OfficialMetric.CASE_COUNT, OfficialAgeGroup.ALL, OfficialRegionLevel.NATION, "00", "전국", OfficialPeriodType.WEEK, 2026, 38,
            LocalDate.of(2026, 9, 13), LocalDate.of(2026, 9, 19), new BigDecimal("42")));
    }

    @Test
    @DisplayName("계 행(TOTAL)과 값 null 도 그대로 옮긴다 (0 과 구분한다)")
    void keepsTotalAndNullValue() {
        SentinelPathogenRow total = new SentinelPathogenRow(2026, 40, "TOTAL", "계", "계", null);

        OfficialRecord record = processor.pathogenRecords(ENTERIC, List.of(total)).get(0);

        assertThat(record.program()).isEqualTo(OfficialProgram.ENTERIC);
        assertThat(record.diseaseKey()).isEqualTo("TOTAL");
        assertThat(record.diseaseGroup()).isEqualTo("계");
        assertThat(record.metricValue()).isNull();
    }

    @Test
    @DisplayName("인플루엔자 행은 키 ILI · 이름 '인플루엔자 의사환자 분율' · 분류 없음 · 1,000명당 분율 · 행의 연령대 · 전국 · WEEK 다")
    void convertsInfluenzaRow() {
        SentinelIliRow row = new SentinelIliRow(2026, 39, OfficialAgeGroup.AGE_65_PLUS, new BigDecimal("1.25"));

        List<OfficialRecord> records = processor.influenzaRecords(SentinelRequest.season(2026), List.of(row));

        assertThat(records).containsExactly(new OfficialRecord(OfficialSource.KDCA_SENTINEL, OfficialProgram.INFLUENZA_ILI, "ILI",
            "인플루엔자 의사환자 분율", null, OfficialMetric.ILI_PER_1000, OfficialAgeGroup.AGE_65_PLUS, OfficialRegionLevel.NATION, "00", "전국",
            OfficialPeriodType.WEEK, 2026, 39, LocalDate.of(2026, 9, 20), LocalDate.of(2026, 9, 26), new BigDecimal("1.25")));
    }

    @Test
    @DisplayName("절기 끝 연도의 주는 그 연도로 들어간다 — 2026–2027 절기의 2027년 1주는 2026-12-27(일)에 시작한다")
    void usesRowYearForSeasonEndWeeks() {
        SentinelIliRow row = new SentinelIliRow(2027, 1, OfficialAgeGroup.AGE_0, null);

        OfficialRecord record = processor.influenzaRecords(SentinelRequest.season(2026), List.of(row)).get(0);

        assertThat(record.periodYear()).isEqualTo(2027);
        assertThat(record.periodStart()).isEqualTo(LocalDate.of(2026, 12, 27));
        assertThat(record.metricValue()).isNull();
    }

    @Test
    @DisplayName("52주인 해(2025)의 53주는 그 요청의 RESPONSE_INVALID 다 — 메시지에 request_key 와 사유")
    void rejectsWeekBeyondYear() {
        SentinelIliRow ok = new SentinelIliRow(2025, 52, OfficialAgeGroup.AGE_0, BigDecimal.ONE);
        SentinelIliRow week53 = new SentinelIliRow(2025, 53, OfficialAgeGroup.AGE_0, BigDecimal.ONE);

        assertThatThrownBy(() -> processor.influenzaRecords(SentinelRequest.season(2025), List.of(ok, week53)))
            .isInstanceOfSatisfying(SentinelImportException.class, exception -> {
                assertThat(exception.getErrorCode()).isEqualTo(SentinelImportErrorCode.RESPONSE_INVALID);
                assertThat(exception.getMessage()).startsWith("[SENTINEL_IMPORT_004]").contains("operation=sentinel:influenza:2025-2026", "week=53");
                assertThat(exception.getCause()).isInstanceOf(IllegalArgumentException.class);
            });
        assertThatThrownBy(() -> processor.pathogenRecords(ENTERIC,
            List.of(new SentinelPathogenRow(2025, 53, "NOV", "노로바이러스", "바이러스", BigDecimal.ONE))))
            .isInstanceOfSatisfying(SentinelImportException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(SentinelImportErrorCode.RESPONSE_INVALID));
    }

    @Test
    @DisplayName("빈 응답은 빈 목록이다 — 0행도 적재 이력 한 행(imported 0)으로 남길 재료다")
    void convertsEmptyRows() {
        assertThat(processor.influenzaRecords(SentinelRequest.season(2026), List.of())).isEmpty();
        assertThat(processor.pathogenRecords(ENTERIC, List.of())).isEmpty();
    }
}
