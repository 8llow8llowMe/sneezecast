package com.sneezecast.domainlayer.official.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.sneezecast.domainlayer.official.OfficialH2TestSupport;
import com.sneezecast.domainlayer.official.domain.enums.OfficialAgeGroup;
import com.sneezecast.domainlayer.official.domain.enums.OfficialMetric;
import com.sneezecast.domainlayer.official.domain.enums.OfficialProgram;
import com.sneezecast.domainlayer.official.domain.enums.OfficialRegionLevel;
import com.sneezecast.domainlayer.official.domain.enums.OfficialSource;
import com.sneezecast.domainlayer.official.domain.model.OfficialObservation;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 계열별 최신 기간 조회(QueryDSL 두 번 + 계열 대조)를 실제 스키마(H2 MySQL 모드)에 질의해 본다. 커스텀 리포지토리는 컴파일로 검증되지 않는다
 * (coding-conventions §8-4).
 */
class OfficialSurveillanceRepositoryAdapterTest extends OfficialH2TestSupport {

    private static final LocalDate W37 = LocalDate.of(2026, 9, 6);
    private static final LocalDate W38 = LocalDate.of(2026, 9, 13);
    private static final LocalDate W39 = LocalDate.of(2026, 9, 20);
    private static final LocalDate W40 = LocalDate.of(2026, 9, 27);

    @Autowired
    private OfficialSurveillanceRepositoryAdapter officialSurveillanceRepositoryAdapter;

    @BeforeEach
    void setUp() {
        clearOfficial();
    }

    @Test
    @DisplayName("자료가 없으면 빈 목록이다")
    void emptyTableReturnsEmpty() {
        assertThat(officialSurveillanceRepositoryAdapter.findLatestWeekly(Map.of())).isEmpty();
        assertThat(officialSurveillanceRepositoryAdapter.findLatestWeekly(Map.of(OfficialProgram.NOTIFIABLE, W38))).isEmpty();
    }

    @Test
    @DisplayName("여러 주가 있으면 계열마다 가장 최근 주만 준다 — 계열끼리 기준 주가 달라도 각자의 최신 주다")
    void picksLatestWeekPerSeries() {
        insertWeekly(OfficialProgram.ARI, "TOTAL", W37, new BigDecimal("1400"));
        insertWeekly(OfficialProgram.ARI, "TOTAL", W39, new BigDecimal("1520"));
        insertWeekly(OfficialProgram.ARI, "TOTAL", W38, new BigDecimal("1480"));
        // 코로나19 는 39주 칸이 아직 집계 중이라 적재되지 않았다 — 38주가 이 계열의 최신이다.
        insertWeekly(OfficialProgram.ARI, "ND0022", W37, new BigDecimal("280"));
        insertWeekly(OfficialProgram.ARI, "ND0022", W38, new BigDecimal("308"));

        assertThat(officialSurveillanceRepositoryAdapter.findLatestWeekly(Map.of()))
            .extracting(OfficialObservation::diseaseKey, OfficialObservation::periodStart, OfficialObservation::metricValue)
            .containsExactlyInAnyOrder(
                tuple("TOTAL", W39, new BigDecimal("1520.00")),
                tuple("ND0022", W38, new BigDecimal("308.00")));
    }

    @Test
    @DisplayName("같은 감염병 키라도 프로그램이 다르면 다른 계열이다 — 프로그램끼리 섞지 않는다")
    void programsAreSeparateSeries() {
        insertWeekly(OfficialProgram.ARI, "TOTAL", W39, new BigDecimal("1520"));
        insertWeekly(OfficialProgram.ENTERIC, "TOTAL", W37, new BigDecimal("410"));
        insertWeekly(OfficialProgram.ENTERIC, "TOTAL", W38, new BigDecimal("432"));

        assertThat(officialSurveillanceRepositoryAdapter.findLatestWeekly(Map.of()))
            .extracting(OfficialObservation::program, OfficialObservation::source, OfficialObservation::periodStart)
            .containsExactlyInAnyOrder(
                tuple(OfficialProgram.ARI, OfficialSource.KDCA_SENTINEL, W39),
                tuple(OfficialProgram.ENTERIC, OfficialSource.KDCA_SENTINEL, W38));
    }

    @Test
    @DisplayName("인플루엔자처럼 연령대별 행만 오는 계열은 최신 주의 연령대 행을 모두 준다 — 지난 주 행은 빼고")
    void returnsEveryAgeGroupOfTheLatestWeek() {
        insertWeekly(OfficialProgram.INFLUENZA_ILI, "ILI", OfficialMetric.ILI_PER_1000, OfficialAgeGroup.AGE_0, W38, new BigDecimal("3.1"), SYNCED_AT);
        insertWeekly(OfficialProgram.INFLUENZA_ILI, "ILI", OfficialMetric.ILI_PER_1000, OfficialAgeGroup.AGE_0, W39, new BigDecimal("4.2"), SYNCED_AT);
        insertWeekly(OfficialProgram.INFLUENZA_ILI, "ILI", OfficialMetric.ILI_PER_1000, OfficialAgeGroup.AGE_65_PLUS, W38, new BigDecimal("1.0"),
            SYNCED_AT);
        insertWeekly(OfficialProgram.INFLUENZA_ILI, "ILI", OfficialMetric.ILI_PER_1000, OfficialAgeGroup.AGE_65_PLUS, W39, null, SYNCED_AT);

        assertThat(officialSurveillanceRepositoryAdapter.findLatestWeekly(Map.of()))
            .extracting(OfficialObservation::ageGroup, OfficialObservation::periodStart, OfficialObservation::metricValue)
            .containsExactlyInAnyOrder(
                tuple(OfficialAgeGroup.AGE_0, W39, new BigDecimal("4.20")),
                tuple(OfficialAgeGroup.AGE_65_PLUS, W39, null));
    }

    @Test
    @DisplayName("연 단위(시도) 행은 읽지 않는다 — 기간 시작일이 늦어도 주 단위 최신 주에 끼지 않는다")
    void ignoresYearlyRows() {
        insertWeekly(OfficialProgram.NOTIFIABLE, "수두", W38, new BigDecimal("812"));
        insertSidoYearly("수두", "01", "서울", 2027, new BigDecimal("15000"));

        List<OfficialObservation> rows = officialSurveillanceRepositoryAdapter.findLatestWeekly(Map.of());

        assertThat(rows).extracting(OfficialObservation::regionLevel, OfficialObservation::periodStart)
            .containsExactly(tuple(OfficialRegionLevel.NATION, W38));
    }

    @Test
    @DisplayName("한도가 걸린 프로그램은 한도보다 늦은 주를 최신 후보에서 뺀다 — 한도가 없는 프로그램은 그대로다")
    void limitAppliesOnlyToItsProgram() {
        insertWeekly(OfficialProgram.NOTIFIABLE, "수두", W38, new BigDecimal("812"));
        insertWeekly(OfficialProgram.NOTIFIABLE, "수두", W39, new BigDecimal("540"));
        insertWeekly(OfficialProgram.NOTIFIABLE, "수두", W40, new BigDecimal("120"));
        insertWeekly(OfficialProgram.ARI, "TOTAL", W40, new BigDecimal("1520"));

        assertThat(officialSurveillanceRepositoryAdapter.findLatestWeekly(Map.of(OfficialProgram.NOTIFIABLE, W38)))
            .extracting(OfficialObservation::program, OfficialObservation::periodStart, OfficialObservation::metricValue)
            .containsExactlyInAnyOrder(
                tuple(OfficialProgram.NOTIFIABLE, W38, new BigDecimal("812.00")),
                tuple(OfficialProgram.ARI, W40, new BigDecimal("1520.00")));
    }

    @Test
    @DisplayName("한도 이하의 주가 하나도 없는 계열은 나오지 않는다 — 한도보다 늦은 주로 대신 채우지 않는다")
    void seriesWithOnlyLaterWeeksIsAbsent() {
        insertWeekly(OfficialProgram.NOTIFIABLE, "수두", W40, new BigDecimal("120"));

        assertThat(officialSurveillanceRepositoryAdapter.findLatestWeekly(Map.of(OfficialProgram.NOTIFIABLE, W38))).isEmpty();
    }

    @Test
    @DisplayName("다른 계열의 최신 주에 걸린 지난 주 행은 버린다 — 같은 프로그램 · 같은 주라도 그 계열의 최신이 아니면 빠진다")
    void dropsRowsOfOtherSeriesLatestWeek() {
        insertWeekly(OfficialProgram.ARI, "TOTAL", W38, new BigDecimal("1480"));
        insertWeekly(OfficialProgram.ARI, "TOTAL", W39, new BigDecimal("1520"));
        insertWeekly(OfficialProgram.ARI, "ND0022", W38, new BigDecimal("308"));

        assertThat(officialSurveillanceRepositoryAdapter.findLatestWeekly(Map.of()))
            .extracting(OfficialObservation::diseaseKey, OfficialObservation::periodStart)
            .containsExactlyInAnyOrder(tuple("TOTAL", W39), tuple("ND0022", W38));
    }

    @Test
    @DisplayName("기간 · 지역 · 연도 · 주차 · 적재 시각을 도메인으로 그대로 옮긴다")
    void mapsColumns() {
        insertWeekly(OfficialProgram.ENTERIC, "ND0615", W38, new BigDecimal("37"));

        assertThat(officialSurveillanceRepositoryAdapter.findLatestWeekly(Map.of())).singleElement().satisfies(row -> {
            assertThat(row.source()).isEqualTo(OfficialSource.KDCA_SENTINEL);
            assertThat(row.diseaseName()).isEqualTo("ND0615 이름");
            assertThat(row.diseaseGroup()).isEqualTo("바이러스");
            assertThat(row.metric()).isEqualTo(OfficialMetric.CASE_COUNT);
            assertThat(row.regionCode()).isEqualTo("00");
            assertThat(row.regionName()).isEqualTo("전국");
            assertThat(row.periodYear()).isEqualTo(2026);
            assertThat(row.periodWeek()).isEqualTo(38);
            assertThat(row.periodEnd()).isEqualTo(LocalDate.of(2026, 9, 19));
            assertThat(row.syncedAt()).isEqualTo(SYNCED_AT);
        });
    }
}
