package com.sneezecast.domainlayer.official.application.service.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sneezecast.domainlayer.official.application.info.OfficialLatestInfo;
import com.sneezecast.domainlayer.official.application.port.out.OfficialSurveillanceRepositoryPort;
import com.sneezecast.domainlayer.official.application.service.OfficialNotifiableProperties;
import com.sneezecast.domainlayer.official.domain.enums.OfficialAgeGroup;
import com.sneezecast.domainlayer.official.domain.enums.OfficialMetric;
import com.sneezecast.domainlayer.official.domain.enums.OfficialProgram;
import com.sneezecast.domainlayer.official.domain.enums.OfficialRegionLevel;
import com.sneezecast.domainlayer.official.domain.enums.OfficialSource;
import com.sneezecast.domainlayer.official.domain.model.OfficialObservation;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class OfficialQueryProcessorTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDate W38 = LocalDate.of(2026, 9, 13);
    private static final LocalDate W39 = LocalDate.of(2026, 9, 20);
    private static final LocalDateTime SYNCED_AT = LocalDateTime.of(2026, 9, 25, 6, 0, 4);

    private final OfficialSurveillanceRepositoryPort port = mock(OfficialSurveillanceRepositoryPort.class);

    @ParameterizedTest(name = "{0} KST, 지연 {1}주 → {2}")
    @CsvSource({
        // 2026-09-30(수)은 40주(09-27 ~ 10-03). 2주 전 = 38주(09-13 시작) — data-api-analysis §2-3 의 예와 같다.
        "2026-09-30T10:00, 2, 2026-09-13",
        // 질병관리청 주는 일요일에 시작한다 — 일요일 0시가 되면 한 주 넘어간다.
        "2026-10-03T23:59, 2, 2026-09-13",
        "2026-10-04T00:00, 2, 2026-09-20",
        // 0 이면 지금 주까지 보인다.
        "2026-09-30T10:00, 0, 2026-09-27",
        // 연도 경계: 2026년 1주는 2025-12-28 ~ 2026-01-03.
        "2026-01-01T09:00, 1, 2025-12-21"
    })
    @DisplayName("전수신고는 지금 주(일요일 시작, KST)에서 설정한 주 수만큼 전의 주까지만 후보로 넘긴다")
    void limitsNotifiableToLaggedWeek(String now, int lagWeeks, String expectedLimit) {
        when(port.findLatestWeekly(any())).thenReturn(List.of());

        processorAt(now, lagWeeks).getLatest();

        verify(port).findLatestWeekly(Map.of(OfficialProgram.NOTIFIABLE, LocalDate.parse(expectedLimit)));
    }

    @Test
    @DisplayName("자료가 없으면 프로그램이 빈 목록이다 (null 이 아니다)")
    void emptyWhenNoRows() {
        when(port.findLatestWeekly(any())).thenReturn(List.of());

        assertThat(processorAt("2026-09-30T10:00", 2).getLatest().programs()).isEmpty();
    }

    @Test
    @DisplayName("프로그램은 선언 순서, 프로그램 안은 감염병 키 순, 값은 연령대 순으로 묶는다 — 받은 순서와 무관하다")
    void groupsAndOrders() {
        when(port.findLatestWeekly(any())).thenReturn(List.of(
            ari("TOTAL", W39, "1520"),
            ili(OfficialAgeGroup.AGE_65_PLUS, "1.00"),
            notifiable("수두", W38, "812"),
            ari("ND0022", W38, "308"),
            ili(OfficialAgeGroup.AGE_0, "4.20")));

        List<OfficialLatestInfo.Program> programs = processorAt("2026-09-30T10:00", 2).getLatest().programs();

        assertThat(programs).extracting(OfficialLatestInfo.Program::program)
            .containsExactly(OfficialProgram.NOTIFIABLE, OfficialProgram.INFLUENZA_ILI, OfficialProgram.ARI);
        assertThat(programs.get(2).diseases()).extracting(OfficialLatestInfo.Disease::diseaseKey, OfficialLatestInfo.Disease::periodStart)
            .containsExactly(tuple("ND0022", W38), tuple("TOTAL", W39));
        assertThat(programs.get(1).diseases()).singleElement().satisfies(disease -> {
            assertThat(disease.metric()).isEqualTo(OfficialMetric.ILI_PER_1000);
            assertThat(disease.values()).extracting(OfficialLatestInfo.Value::ageGroup, OfficialLatestInfo.Value::value)
                .containsExactly(tuple(OfficialAgeGroup.AGE_0, new BigDecimal("4.20")), tuple(OfficialAgeGroup.AGE_65_PLUS, new BigDecimal("1.00")));
        });
    }

    @Test
    @DisplayName("원문 주소는 확인된 표본감시 화면만 싣고 전수신고는 null 이다 — 출처는 행의 원천이다")
    void sourceAndLinkPerProgram() {
        when(port.findLatestWeekly(any())).thenReturn(List.of(
            notifiable("수두", W38, "812"),
            ili(OfficialAgeGroup.AGE_0, "4.20"),
            ari("TOTAL", W39, "1520"),
            observation(OfficialProgram.ENTERIC, OfficialSource.KDCA_SENTINEL, "TOTAL", OfficialMetric.CASE_COUNT, OfficialAgeGroup.ALL, W39, "432", SYNCED_AT)));

        assertThat(processorAt("2026-09-30T10:00", 2).getLatest().programs())
            .extracting(OfficialLatestInfo.Program::program, OfficialLatestInfo.Program::source, OfficialLatestInfo.Program::sourceUrl)
            .containsExactly(
                tuple(OfficialProgram.NOTIFIABLE, OfficialSource.KDCA_NOTIFIABLE, null),
                tuple(OfficialProgram.INFLUENZA_ILI, OfficialSource.KDCA_SENTINEL, "https://dportal.kdca.go.kr/pot/is/st/influ.do"),
                tuple(OfficialProgram.ARI, OfficialSource.KDCA_SENTINEL, "https://dportal.kdca.go.kr/pot/is/st/ari.do"),
                tuple(OfficialProgram.ENTERIC, OfficialSource.KDCA_SENTINEL, "https://dportal.kdca.go.kr/pot/is/st/gstrnftn.do"));
    }

    @Test
    @DisplayName("계열의 적재 시각은 그 주 행들 중 가장 늦은 시각이고, 빈 칸 값(null)은 0 으로 바꾸지 않는다")
    void syncedAtIsLatestAndNullStaysNull() {
        LocalDateTime later = SYNCED_AT.plusMinutes(3);
        when(port.findLatestWeekly(any())).thenReturn(List.of(
            observation(OfficialProgram.INFLUENZA_ILI, OfficialSource.KDCA_SENTINEL, "ILI", OfficialMetric.ILI_PER_1000, OfficialAgeGroup.AGE_0, W39, "4.20",
                SYNCED_AT),
            observation(OfficialProgram.INFLUENZA_ILI, OfficialSource.KDCA_SENTINEL, "ILI", OfficialMetric.ILI_PER_1000, OfficialAgeGroup.AGE_1_6, W39, null,
                later)));

        OfficialLatestInfo.Disease disease = processorAt("2026-09-30T10:00", 2).getLatest().programs().getFirst().diseases().getFirst();

        assertThat(disease.syncedAt()).isEqualTo(later);
        assertThat(disease.values()).extracting(OfficialLatestInfo.Value::value).containsExactly(new BigDecimal("4.20"), null);
    }

    private OfficialQueryProcessor processorAt(String kstDateTime, int lagWeeks) {
        Clock clock = Clock.fixed(ZonedDateTime.of(LocalDateTime.parse(kstDateTime), KST).toInstant(), KST);
        return new OfficialQueryProcessor(port, new OfficialNotifiableProperties(lagWeeks), clock);
    }

    private static OfficialObservation notifiable(String diseaseKey, LocalDate start, String value) {
        return observation(OfficialProgram.NOTIFIABLE, OfficialSource.KDCA_NOTIFIABLE, diseaseKey, OfficialMetric.CASE_COUNT, OfficialAgeGroup.ALL, start,
            value, SYNCED_AT);
    }

    private static OfficialObservation ari(String diseaseKey, LocalDate start, String value) {
        return observation(OfficialProgram.ARI, OfficialSource.KDCA_SENTINEL, diseaseKey, OfficialMetric.CASE_COUNT, OfficialAgeGroup.ALL, start, value,
            SYNCED_AT);
    }

    private static OfficialObservation ili(OfficialAgeGroup ageGroup, String value) {
        return observation(OfficialProgram.INFLUENZA_ILI, OfficialSource.KDCA_SENTINEL, "ILI", OfficialMetric.ILI_PER_1000, ageGroup, W39, value, SYNCED_AT);
    }

    private static OfficialObservation observation(OfficialProgram program, OfficialSource source, String diseaseKey, OfficialMetric metric,
        OfficialAgeGroup ageGroup, LocalDate start, String value, LocalDateTime syncedAt) {
        return OfficialObservation.builder()
            .source(source).program(program).diseaseKey(diseaseKey).diseaseName(diseaseKey + " 이름").metric(metric).ageGroup(ageGroup)
            .regionLevel(OfficialRegionLevel.NATION).regionCode("00").regionName("전국")
            .periodYear(start.plusDays(6).getYear()).periodWeek(38).periodStart(start).periodEnd(start.plusDays(6))
            .metricValue(value == null ? null : new BigDecimal(value)).syncedAt(syncedAt)
            .build();
    }
}
