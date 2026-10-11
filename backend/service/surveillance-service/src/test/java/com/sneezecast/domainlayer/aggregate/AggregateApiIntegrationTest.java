package com.sneezecast.domainlayer.aggregate;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sneezecast.JwtTestTokens;
import com.sneezecast.domainlayer.district.DistrictH2TestSupport;
import com.sneezecast.domainlayer.report.domain.model.ReportWeek;
import com.sneezecast.security.common.enums.SecurityRole;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 공개 집계 API 를 보안 필터 체인부터 H2 까지 실제 빈으로 부른다. 비회원(토큰 없음)과 회원 토큰 모두 통과해야 하고, 자료 부족이면 행에 숫자가 있어도
 * 응답에 수치가 없어야 한다. 집계 행은 JDBC 로 넣는다 — 스케줄러는 테스트 컨텍스트에서 꺼져 있다.
 *
 * <p>현재 주는 컨텍스트의 {@code Clock} 빈(시스템 시각)으로 정한다. 행은 2주 전 · 3주 전 주에 넣고 {@code week} 로 지정해, 실행 중 주가 바뀌어도
 * 행 쪽 단언이 흔들리지 않게 한다.
 */
class AggregateApiIntegrationTest extends DistrictH2TestSupport {

    private static final String SEOGYO = "11440660";
    private static final String HAPJEONG = "11440680";
    private static final String MANGWON_RETIRED = "11440690";
    private static final String YEOKSAM = "11230510";
    private static final LocalDateTime CALCULATED_AT = LocalDateTime.of(2026, 10, 5, 0, 10);
    private static final AtomicLong IDS = new AtomicLong(9_000_000L);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private Clock clock;

    private ReportWeek week;
    private ReportWeek previousWeek;

    @BeforeEach
    void setUp() {
        clearDistricts();
        jdbcTemplate.update("DELETE FROM district_weekly_aggregate");
        insertActive(SEOGYO, "서교동", "서울특별시", "마포구");
        insertActive(HAPJEONG, "합정동", "서울특별시", "마포구");
        insert(MANGWON_RETIRED, "망원동", "서울특별시", "마포구", RETIRED_IN_2024);
        insertActive(YEOKSAM, "역삼1동", "서울특별시", "강남구");
        week = ReportWeek.of(clock.instant()).minusWeeks(2);
        previousWeek = week.previous();
    }

    @Test
    @DisplayName("토큰 없이 집계 행이 없는 동을 부르면 200 이고 자료 부족 · 참여 0 · 수치 없음이다 (주를 생략하면 현재 주)")
    void anonymousWithoutRowIsInsufficient() throws Exception {
        mockMvc.perform(get("/api/v1/districts/{code}/weekly-aggregate", SEOGYO))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dataHeader.success").value(true))
            .andExpect(jsonPath("$.dataBody.isoWeek").value(ReportWeek.of(clock.instant()).value()))
            .andExpect(jsonPath("$.dataBody.finalized").value(false))
            .andExpect(jsonPath("$.dataBody.publicThreshold").value(100))
            .andExpect(jsonPath("$.dataBody.slightDeltaPp").value(3))
            .andExpect(jsonPath("$.dataBody.highDeltaPp").value(8))
            .andExpect(jsonPath("$.dataBody.district.code").value(SEOGYO))
            .andExpect(jsonPath("$.dataBody.district.name").value("서교동"))
            .andExpect(jsonPath("$.dataBody.district.sigungu").value("서울특별시 마포구"))
            .andExpect(jsonPath("$.dataBody.district.active").value(true))
            .andExpect(jsonPath("$.dataBody.district.level.code").value("INSUFFICIENT"))
            .andExpect(jsonPath("$.dataBody.district.insufficientReason.code").value("LOW_SAMPLE"))
            .andExpect(jsonPath("$.dataBody.district.participantCount").value(0))
            .andExpect(jsonPath("$.dataBody.district.symptomRate").doesNotExist())
            .andExpect(jsonPath("$.dataBody.district.groups").doesNotExist());
    }

    @Test
    @DisplayName("자료 부족 행은 숫자가 있어도 비율 · 증상 수 · 기준선 · 증상군을 내리지 않는다 — 참여 · 이유 · 마감 · 계산 시각만")
    void insufficientRowHidesNumbers() throws Exception {
        // 참여 150 · 증상 60 — 숫자는 있지만 기준선이 없어 자료 부족이다.
        insertRow(SEOGYO, week, 150, 60, 50, 20, "INSUFFICIENT", "NO_BASELINE", null, null, true);

        mockMvc.perform(get("/api/v1/districts/{code}/weekly-aggregate", SEOGYO).param("week", week.value()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dataBody.isoWeek").value(week.value()))
            .andExpect(jsonPath("$.dataBody.finalized").value(true))
            .andExpect(jsonPath("$.dataBody.district.level.code").value("INSUFFICIENT"))
            .andExpect(jsonPath("$.dataBody.district.insufficientReason.code").value("NO_BASELINE"))
            .andExpect(jsonPath("$.dataBody.district.participantCount").value(150))
            .andExpect(jsonPath("$.dataBody.district.calculatedAt").exists())
            .andExpect(jsonPath("$.dataBody.district.symptomaticCount").doesNotExist())
            .andExpect(jsonPath("$.dataBody.district.symptomRate").doesNotExist())
            .andExpect(jsonPath("$.dataBody.district.baselineRate").doesNotExist())
            .andExpect(jsonPath("$.dataBody.district.groups").doesNotExist());
    }

    @Test
    @DisplayName("회원 토큰으로도 같은 공개 응답이다 — 수치가 있는 주는 비율 · 기준선 · 증상군 비율 · 전주 대비 변화를 준다")
    void memberSeesMeasuredWeek() throws Exception {
        // 이번 주 20/120 = 16.7%, 호흡기 15/120 = 12.5%, 장관 9/120 = 7.5%, 기준선 40/400 = 10.0%
        insertRow(SEOGYO, week, 120, 20, 15, 9, "SLIGHT", null, 400, 40, false);
        // 전주 호흡기 10/200 = 5% (+7.5%p → SLIGHT), 장관 21/200 = 10.5% (−3%p → DOWN)
        insertRow(SEOGYO, previousWeek, 200, 30, 10, 21, "NORMAL", null, 400, 40, true);

        mockMvc.perform(get("/api/v1/districts/{code}/weekly-aggregate", SEOGYO).param("week", week.value())
                .header(HttpHeaders.AUTHORIZATION, JwtTestTokens.bearer(ACCESS_KEY, 1L, SecurityRole.USER)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dataBody.finalized").value(false))
            .andExpect(jsonPath("$.dataBody.district.level.code").value("SLIGHT"))
            .andExpect(jsonPath("$.dataBody.district.level.name").value("조금 늘었어요"))
            .andExpect(jsonPath("$.dataBody.district.insufficientReason").doesNotExist())
            .andExpect(jsonPath("$.dataBody.district.participantCount").value(120))
            .andExpect(jsonPath("$.dataBody.district.symptomaticCount").value(20))
            .andExpect(jsonPath("$.dataBody.district.symptomRate").value(16.7))
            .andExpect(jsonPath("$.dataBody.district.baselineRate").value(10.0))
            .andExpect(jsonPath("$.dataBody.district.groups.length()").value(2))
            .andExpect(jsonPath("$.dataBody.district.groups[0].group.code").value("RESPIRATORY"))
            .andExpect(jsonPath("$.dataBody.district.groups[0].rate").value(12.5))
            .andExpect(jsonPath("$.dataBody.district.groups[0].trend.code").value("SLIGHT"))
            .andExpect(jsonPath("$.dataBody.district.groups[1].group.code").value("ENTERIC"))
            .andExpect(jsonPath("$.dataBody.district.groups[1].rate").value(7.5))
            .andExpect(jsonPath("$.dataBody.district.groups[1].trend.code").value("DOWN"));
    }

    @Test
    @DisplayName("지도 — 시군구의 현행 동 전부(폐지 동 제외)에 찾은 동네를 맨 앞에 더한다. 시군구 밖 · 행 없음이어도 자료 부족으로 담긴다")
    void mapAlwaysCarriesIncludedDistrict() throws Exception {
        insertRow(SEOGYO, week, 120, 12, 12, 0, "NORMAL", null, 400, 40, true);
        insertRow(MANGWON_RETIRED, week, 130, 13, 13, 0, "NORMAL", null, 400, 40, true);

        mockMvc.perform(get("/api/v1/districts/weekly-aggregates").param("sigunguCode", "11440").param("include", YEOKSAM).param("week", week.value()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dataBody.isoWeek").value(week.value()))
            .andExpect(jsonPath("$.dataBody.finalized").value(true))
            .andExpect(jsonPath("$.dataBody.districts.length()").value(3))
            .andExpect(jsonPath("$.dataBody.districts[0].code").value(YEOKSAM))
            .andExpect(jsonPath("$.dataBody.districts[0].sigungu").value("서울특별시 강남구"))
            .andExpect(jsonPath("$.dataBody.districts[0].level.code").value("INSUFFICIENT"))
            .andExpect(jsonPath("$.dataBody.districts[0].participantCount").value(0))
            .andExpect(jsonPath("$.dataBody.districts[0].symptomRate").doesNotExist())
            .andExpect(jsonPath("$.dataBody.districts[1].code").value(SEOGYO))
            .andExpect(jsonPath("$.dataBody.districts[1].level.code").value("NORMAL"))
            .andExpect(jsonPath("$.dataBody.districts[1].symptomRate").value(10.0))
            .andExpect(jsonPath("$.dataBody.districts[2].code").value(HAPJEONG))
            .andExpect(jsonPath("$.dataBody.districts[2].level.code").value("INSUFFICIENT"));
    }

    @Test
    @DisplayName("지도 — 폐지된 찾은 동네도 active=false 로 담는다")
    void mapCarriesRetiredInclude() throws Exception {
        mockMvc.perform(get("/api/v1/districts/weekly-aggregates").param("sigunguCode", "11440").param("include", MANGWON_RETIRED))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dataBody.districts.length()").value(3))
            .andExpect(jsonPath("$.dataBody.districts[0].code").value(MANGWON_RETIRED))
            .andExpect(jsonPath("$.dataBody.districts[0].active").value(false));
    }

    @Test
    @DisplayName("없는 코드는 404 AGGREGATE_001, 미래 주 · 52주 전은 400 AGGREGATE_002, 형식 오류는 AGGREGATE_101 봉투다")
    void errorsAreEnveloped() throws Exception {
        ReportWeek current = ReportWeek.of(clock.instant());
        ReportWeek next = ReportWeek.of(clock.instant().plus(Duration.ofDays(7)));

        mockMvc.perform(get("/api/v1/districts/{code}/weekly-aggregate", "99999999"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("AGGREGATE_001"));
        mockMvc.perform(get("/api/v1/districts/weekly-aggregates").param("sigunguCode", "11440").param("include", "99999999"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("AGGREGATE_001"));
        mockMvc.perform(get("/api/v1/districts/{code}/weekly-aggregate", SEOGYO).param("week", next.value()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("AGGREGATE_002"));
        mockMvc.perform(get("/api/v1/districts/{code}/weekly-aggregate", SEOGYO).param("week", current.minusWeeks(52).value()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("AGGREGATE_002"));
        mockMvc.perform(get("/api/v1/districts/{code}/weekly-aggregate", "1144066"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("AGGREGATE_101"));
    }

    @Test
    @DisplayName("행정동 단건 조회는 그대로다 — 집계 경로가 /{code} 를 가로채지 않는다")
    void districtLookupIsUnchanged() throws Exception {
        mockMvc.perform(get("/api/v1/districts/{code}", SEOGYO))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dataBody.code").value(SEOGYO))
            .andExpect(jsonPath("$.dataBody.level").doesNotExist());
    }

    private void insertRow(String code, ReportWeek isoWeek, int participants, int symptomatic, int respiratory, int enteric, String level, String reason,
        Integer baselineParticipants, Integer baselineSymptomatic, boolean finalized) {
        Timestamp calculatedAt = Timestamp.valueOf(CALCULATED_AT);
        jdbcTemplate.update("""
                INSERT INTO district_weekly_aggregate (id, district_code, iso_week, participant_count, symptomatic_count, respiratory_count, enteric_count,
                    revised_report_count, level, insufficient_reason, baseline_participant_count, baseline_symptomatic_count, rule_version, calculated_at,
                    finalized_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?, ?, '2026-10-08', ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)""",
            IDS.incrementAndGet(), code, isoWeek.value(), participants, symptomatic, respiratory, enteric, level, reason, baselineParticipants,
            baselineSymptomatic, calculatedAt, finalized ? calculatedAt : null);
    }
}
