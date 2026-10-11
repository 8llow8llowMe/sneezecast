package com.sneezecast.domainlayer.advisory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sneezecast.JwtTestTokens;
import com.sneezecast.domainlayer.district.DistrictH2TestSupport;
import com.sneezecast.domainlayer.report.domain.model.ReportWeek;
import com.sneezecast.security.common.enums.SecurityRole;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 검토 후보 API(#266)를 보안 필터 체인부터 H2 까지 실제 빈으로 부른다 — 응답 모양, 자료 부족 후보에 수치 키가 없는지, 안내 상태, 기본 주 · 검증.
 * 권한(비회원 401 · 일반 회원 403)은 {@code AdvisoryOperatorApiIntegrationTest} 의 엔드포인트 목록이 함께 본다. 집계 행은 JDBC 로 넣는다.
 *
 * <p>대상 주는 컨텍스트의 {@code Clock} 빈(시스템 시각)으로 정한 현재 주의 2주 전이다.
 */
class AdvisoryReviewCandidateApiIntegrationTest extends DistrictH2TestSupport {

    private static final String CANDIDATES = "/api/v1/advisories/admin/candidates";
    private static final String SEOGYO = "11440660";
    private static final String MANGWON = "11440690";
    private static final String HAPJEONG = "11440680";
    private static final String YEONNAM = "11440710";
    private static final String[] MEASURED_ONLY_KEYS = {"symptomaticCount", "symptomRate", "baselineRate", "baselineDeltaPp", "groups"};

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private Clock clock;

    private ReportWeek week;
    private long nextRowId;

    @BeforeEach
    void setUp() {
        clearDistricts();
        jdbcTemplate.update("DELETE FROM advisory");
        jdbcTemplate.update("DELETE FROM advisory_history");
        jdbcTemplate.update("DELETE FROM district_weekly_aggregate");
        insertActive(SEOGYO, "서교동", "서울특별시", "마포구");
        insertActive(MANGWON, "망원1동", "서울특별시", "마포구");
        insertActive(HAPJEONG, "합정동", "서울특별시", "마포구");
        insertActive(YEONNAM, "연남동", "서울특별시", "마포구");
        week = ReportWeek.of(clock.instant()).minusWeeks(2);
        nextRowId = 100;
    }

    @Test
    @DisplayName("후보 응답 — 기준선 후보는 수치를, 자료 부족(급변) 급증 후보는 참여 · 전주 참여 · 수정 수만 싣고 수치 키가 없다. 참여 부족 · 신호 없는 동네는 빠진다")
    void candidatesResponse() throws Exception {
        // 서교동: 많이 늘었어요 — 참여 150 · 증상 30(20.0%) · 기준선 60/600(10.0%)
        insertRow(SEOGYO, week, 150, 30, 20, 15, 0, "HIGH", null, 600, 60, true);
        insertRow(SEOGYO, week.previous(), 140, 14, 10, 4, 0, "NORMAL", null, 560, 56, true);
        // 망원1동: 참여 급변(자료 부족) — 전주 100 → 300 은 급증(+200%), 수정 60/300 = 20% 는 반복
        insertRow(MANGWON, week, 300, 90, 60, 30, 60, "INSUFFICIENT", "UNSTABLE", null, null, true);
        insertRow(MANGWON, week.previous(), 100, 10, 5, 5, 0, "NORMAL", null, 400, 40, true);
        // 합정동: 참여 부족 — 수정이 많아도 신호가 없다
        insertRow(HAPJEONG, week, 50, 10, 5, 5, 50, "INSUFFICIENT", "LOW_SAMPLE", null, null, true);
        // 연남동: 평소 수준, 신호 없음
        insertRow(YEONNAM, week, 150, 15, 10, 5, 0, "NORMAL", null, 600, 60, true);

        JsonNode body = dataBody(candidates(week.value())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dataBody.isoWeek").value(week.value()))
            .andExpect(jsonPath("$.dataBody.finalized").value(true))
            .andExpect(jsonPath("$.dataBody.surgeChangePercent").value(30))
            .andExpect(jsonPath("$.dataBody.repeatRevisedPercent").value(20))
            .andExpect(jsonPath("$.dataBody.candidates.length()").value(2)));

        JsonNode measured = body.at("/candidates/0");
        assertThat(measured.get("districtCode").asText()).isEqualTo(SEOGYO);
        assertThat(measured.get("districtName").asText()).isEqualTo("서교동");
        assertThat(measured.get("sigungu").asText()).isEqualTo("서울특별시 마포구");
        assertThat(measured.get("active").asBoolean()).isTrue();
        assertThat(measured.at("/signals/0/code").asText()).isEqualTo("BASELINE");
        assertThat(measured.at("/signals/0/name").asText()).isEqualTo("기준선 대비 변화");
        assertThat(measured.at("/level/code").asText()).isEqualTo("HIGH");
        assertThat(measured.get("insufficientReason").isNull()).as("값이 없는 키는 null 로 둔다").isTrue();
        assertThat(measured.get("draftable").asBoolean()).isTrue();
        assertThat(measured.get("participantCount").asInt()).isEqualTo(150);
        assertThat(measured.get("previousParticipantCount").asInt()).isEqualTo(140);
        assertThat(measured.get("symptomaticCount").asInt()).isEqualTo(30);
        assertThat(measured.get("symptomRate").decimalValue()).isEqualByComparingTo("20.0");
        assertThat(measured.get("baselineRate").decimalValue()).isEqualByComparingTo("10.0");
        assertThat(measured.get("baselineDeltaPp").decimalValue()).isEqualByComparingTo("10.0");
        assertThat(measured.at("/groups/0/group/code").asText()).isEqualTo("RESPIRATORY");
        assertThat(measured.at("/groups/0/count").asInt()).isEqualTo(20);
        assertThat(measured.at("/groups/0/rate").decimalValue()).isEqualByComparingTo("13.3");
        assertThat(measured.has("advisory") && measured.get("advisory").isNull()).as("안내가 없으면 null").isTrue();

        JsonNode insufficient = body.at("/candidates/1");
        assertThat(insufficient.get("districtCode").asText()).isEqualTo(MANGWON);
        assertThat(insufficient.get("signals").findValuesAsText("code")).containsExactly("SURGE", "REPEAT");
        assertThat(insufficient.at("/level/code").asText()).isEqualTo("INSUFFICIENT");
        assertThat(insufficient.at("/insufficientReason/code").asText()).isEqualTo("UNSTABLE");
        assertThat(insufficient.get("draftable").asBoolean()).isFalse();
        assertThat(insufficient.get("participantCount").asInt()).isEqualTo(300);
        assertThat(insufficient.get("previousParticipantCount").asInt()).isEqualTo(100);
        assertThat(insufficient.get("revisedReportCount").asInt()).isEqualTo(60);
        for (String key : MEASURED_ONLY_KEYS) {
            assertThat(insufficient.has(key)).as("자료 부족 후보에는 %s 키가 없다", key).isFalse();
        }
    }

    @Test
    @DisplayName("안내 상태 — 그 주에 안내가 있으면 신호가 없어도 후보이고, ID(문자열) · 상태 · 버전을 싣는다. 전주가 없으면 전주 참여는 null")
    void advisoryStateOnCandidate() throws Exception {
        insertRow(SEOGYO, week, 150, 15, 10, 5, 0, "NORMAL", null, 600, 60, true);
        String created = mockMvc.perform(post("/api/v1/advisories/admin").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("districtCode", SEOGYO, "isoWeek", week.value(), "title", "제목", "body", "본문")))
                .header(HttpHeaders.AUTHORIZATION, operator()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String advisoryId = objectMapper.readTree(created).at("/dataBody/advisoryId").asText();

        JsonNode candidate = dataBody(candidates(week.value()).andExpect(status().isOk())).at("/candidates/0");

        assertThat(candidate.get("districtCode").asText()).isEqualTo(SEOGYO);
        assertThat(candidate.get("signals").isEmpty()).isTrue();
        assertThat(candidate.get("previousParticipantCount").isNull()).isTrue();
        assertThat(candidate.at("/advisory/advisoryId").isTextual()).as("ID 는 문자열이다 (coding-conventions §2-1)").isTrue();
        assertThat(candidate.at("/advisory/advisoryId").asText()).isEqualTo(advisoryId);
        assertThat(candidate.at("/advisory/status/code").asText()).isEqualTo("DRAFT");
        assertThat(candidate.at("/advisory/version").asLong()).isZero();
    }

    @Test
    @DisplayName("주 — 생략하면 현재 주 − 1, 리터럴 /candidates 는 안내 ID 로 읽히지 않는다. 형식 ADVISORY_104, 미래 · 51주보다 이전 · 없는 주 ADVISORY_009")
    void weekParameter() throws Exception {
        ReportWeek current = ReportWeek.of(clock.instant());
        mockMvc.perform(get(CANDIDATES).header(HttpHeaders.AUTHORIZATION, operator()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dataBody.isoWeek").value(current.previous().value()))
            .andExpect(jsonPath("$.dataBody.finalized").value(false))
            .andExpect(jsonPath("$.dataBody.candidates.length()").value(0));

        assertRejected("2026-41", "ADVISORY_104");
        assertRejected(ReportWeek.of(clock.instant().plus(Duration.ofDays(7))).value(), "ADVISORY_009");
        assertRejected(current.minusWeeks(52).value(), "ADVISORY_009");
        // 2027 년에는 53주가 없다 — 형식은 맞지만 없는 주다.
        assertRejected("2027-W53", "ADVISORY_009");
        candidates(current.value()).andExpect(status().isOk()).andExpect(jsonPath("$.dataBody.isoWeek").value(current.value()));
        candidates(current.minusWeeks(51).value()).andExpect(status().isOk());
    }

    private void assertRejected(String week, String resultCode) throws Exception {
        candidates(week)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.dataHeader.success").value(false))
            .andExpect(jsonPath("$.dataHeader.resultCode").value(resultCode));
    }

    private ResultActions candidates(String week) throws Exception {
        return mockMvc.perform(get(CANDIDATES).param("week", week).header(HttpHeaders.AUTHORIZATION, operator()));
    }

    private JsonNode dataBody(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)).get("dataBody");
    }

    private static String operator() {
        return JwtTestTokens.bearer(ACCESS_KEY, 9001L, SecurityRole.OPERATOR);
    }

    private void insertRow(String code, ReportWeek isoWeek, int participants, int symptomatic, int respiratory, int enteric, int revised, String level,
        String reason, Integer baselineParticipants, Integer baselineSymptomatic, boolean finalized) {
        Timestamp at = Timestamp.valueOf(LocalDateTime.of(2026, 10, 5, 0, 10));
        jdbcTemplate.update("""
                INSERT INTO district_weekly_aggregate (id, district_code, iso_week, participant_count, symptomatic_count, respiratory_count, enteric_count,
                  revised_report_count, level, insufficient_reason, baseline_participant_count, baseline_symptomatic_count, rule_version, calculated_at,
                  finalized_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, '2026-10-08', ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)""",
            nextRowId++, code, isoWeek.value(), participants, symptomatic, respiratory, enteric, revised, level, reason, baselineParticipants,
            baselineSymptomatic, at, finalized ? at : null);
    }
}
