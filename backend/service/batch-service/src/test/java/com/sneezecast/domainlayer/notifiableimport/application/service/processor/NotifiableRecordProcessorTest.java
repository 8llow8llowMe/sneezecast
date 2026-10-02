package com.sneezecast.domainlayer.notifiableimport.application.service.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sneezecast.domainlayer.notifiableimport.application.exception.NotifiableImportErrorCode;
import com.sneezecast.domainlayer.notifiableimport.application.exception.NotifiableImportException;
import com.sneezecast.domainlayer.notifiableimport.domain.model.NotifiableRegionMeasure;
import com.sneezecast.domainlayer.notifiableimport.domain.model.NotifiableRegionRow;
import com.sneezecast.domainlayer.notifiableimport.domain.model.NotifiableRequest;
import com.sneezecast.domainlayer.notifiableimport.domain.model.NotifiableWeeklyRow;
import com.sneezecast.domainlayer.official.domain.enums.OfficialAgeGroup;
import com.sneezecast.domainlayer.official.domain.enums.OfficialMetric;
import com.sneezecast.domainlayer.official.domain.enums.OfficialPeriodType;
import com.sneezecast.domainlayer.official.domain.enums.OfficialProgram;
import com.sneezecast.domainlayer.official.domain.enums.OfficialRegionLevel;
import com.sneezecast.domainlayer.official.domain.enums.OfficialSource;
import com.sneezecast.domainlayer.official.domain.model.OfficialRecord;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class NotifiableRecordProcessorTest {

    private final NotifiableRecordProcessor processor = new NotifiableRecordProcessor();

    @Test
    @DisplayName("주별 전국 행은 전수신고 · 발생 수 · 연령 ALL · 전국(00/전국) · WEEK 이고, 기간은 질병관리청 주차(일 ~ 토)다")
    void convertsWeeklyRow() {
        NotifiableWeeklyRow row = new NotifiableWeeklyRow(2026, 38, "엠폭스", "@엠폭스", "제2급", new BigDecimal("12"));

        List<OfficialRecord> records = processor.weeklyRecords(NotifiableRequest.weekly(2026), List.of(row));

        assertThat(records).containsExactly(new OfficialRecord(OfficialSource.KDCA_NOTIFIABLE, OfficialProgram.NOTIFIABLE, "엠폭스", "@엠폭스",
            "제2급", OfficialMetric.CASE_COUNT, OfficialAgeGroup.ALL, OfficialRegionLevel.NATION, "00", "전국", OfficialPeriodType.WEEK, 2026, 38,
            LocalDate.of(2026, 9, 13), LocalDate.of(2026, 9, 19), new BigDecimal("12")));
    }

    @Test
    @DisplayName("값 null · 분류 null 은 그대로 null 이다 (0 과 구분한다)")
    void keepsNullValueAndGroup() {
        NotifiableWeeklyRow row = new NotifiableWeeklyRow(2026, 1, "에볼라바이러스병", "에볼라바이러스병", null, null);

        OfficialRecord record = processor.weeklyRecords(NotifiableRequest.weekly(2026), List.of(row)).get(0);

        assertThat(record.metricValue()).isNull();
        assertThat(record.diseaseGroup()).isNull();
        // 2026년 1주는 2025-12-28(일)에 시작한다.
        assertThat(record.periodStart()).isEqualTo(LocalDate.of(2025, 12, 28));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(NotifiableRegionMeasure.class)
    @DisplayName("시도 연별 행은 요청 지표 · 시도(원천 코드 · 이름) · YEAR · 주차 0 · 1월 1일 ~ 12월 31일이다")
    void convertsRegionRow(NotifiableRegionMeasure measure) {
        NotifiableRegionRow row = new NotifiableRegionRow(2025, "18", "전남광주", "에볼라바이러스병", "에볼라바이러스병", "제1급", new BigDecimal("0.25"));

        OfficialRecord record = processor.regionRecords(NotifiableRequest.region(2025, measure, "18"), List.of(row)).get(0);

        assertThat(record.metric()).isEqualTo(OfficialMetric.valueOf(measure.name()));
        assertThat(record.regionLevel()).isEqualTo(OfficialRegionLevel.SIDO);
        assertThat(record.regionCode()).isEqualTo("18");
        assertThat(record.regionName()).isEqualTo("전남광주");
        assertThat(record.periodType()).isEqualTo(OfficialPeriodType.YEAR);
        assertThat(record.periodYear()).isEqualTo(2025);
        assertThat(record.periodWeek()).isZero();
        assertThat(record.periodStart()).isEqualTo(LocalDate.of(2025, 1, 1));
        assertThat(record.periodEnd()).isEqualTo(LocalDate.of(2025, 12, 31));
        assertThat(record.ageGroup()).isEqualTo(OfficialAgeGroup.ALL);
    }

    @Test
    @DisplayName("52주인 해(2025)의 53주는 그 요청의 KDCA_RESPONSE_INVALID 다 — 메시지에 request_key 와 사유")
    void rejectsWeekBeyondYear() {
        NotifiableWeeklyRow ok = new NotifiableWeeklyRow(2025, 52, "엠폭스", "@엠폭스", "제2급", BigDecimal.ONE);
        NotifiableWeeklyRow week53 = new NotifiableWeeklyRow(2025, 53, "엠폭스", "@엠폭스", "제2급", BigDecimal.ONE);

        assertThatThrownBy(() -> processor.weeklyRecords(NotifiableRequest.weekly(2025), List.of(ok, week53)))
            .isInstanceOfSatisfying(NotifiableImportException.class, exception -> {
                assertThat(exception.getErrorCode()).isEqualTo(NotifiableImportErrorCode.KDCA_RESPONSE_INVALID);
                assertThat(exception.getMessage()).contains("operation=notifiable:periodBasic:week:2025", "week=53");
                assertThat(exception.getCause()).isInstanceOf(IllegalArgumentException.class);
            });
    }

    @Test
    @DisplayName("53주가 있는 해(2022)의 53주는 통과한다")
    void acceptsWeek53InLongYear() {
        NotifiableWeeklyRow row = new NotifiableWeeklyRow(2022, 53, "엠폭스", "@엠폭스", "제2급", BigDecimal.ONE);

        OfficialRecord record = processor.weeklyRecords(NotifiableRequest.weekly(2022), List.of(row)).get(0);

        assertThat(record.periodEnd()).isEqualTo(LocalDate.of(2022, 12, 31));
    }
}
