package com.sneezecast.domainlayer.advisory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 공개 동네 안내 API 와 공개 집계의 발행 안내 요약을 보안 필터 체인부터 H2 까지 실제 빈으로 부른다. 비회원(토큰 없음)과 회원 토큰 모두 통과해야 하고, 발행되지
 * 않은 안내 · 철회된 안내의 내용 · 운영자 이력은 응답에 나오지 않아야 한다. 안내 · 이력 행은 JDBC 로 넣는다(운영자 API 는 #246).
 *
 * <p>주는 컨텍스트의 {@code Clock} 빈(시스템 시각)으로 정한 현재 주의 2주 전이다 — 실행 중 주가 바뀌어도 조회 창 안에 있다.
 */
class AdvisoryApiIntegrationTest extends DistrictH2TestSupport {

    private static final String SEOGYO = "11440660";
    private static final String HAPJEONG = "11440680";
    private static final String MANGWON_RETIRED = "11440690";
    private static final LocalDateTime DAY_1 = LocalDateTime.of(2026, 10, 6, 10, 0);
    private static final LocalDateTime DAY_2 = LocalDateTime.of(2026, 10, 7, 10, 0);
    private static final LocalDateTime DAY_3 = LocalDateTime.of(2026, 10, 8, 10, 0);
    private static final String SECRET_MEMO = "중복 보고를 빼고 다시 냈다 - 운영자 메모";
    private static final AtomicLong IDS = new AtomicLong(7_000_000L);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private Clock clock;

    private ReportWeek week;

    @BeforeEach
    void setUp() {
        clearDistricts();
        jdbcTemplate.update("DELETE FROM advisory");
        jdbcTemplate.update("DELETE FROM advisory_history");
        jdbcTemplate.update("DELETE FROM district_weekly_aggregate");
        insertActive(SEOGYO, "서교동", "서울특별시", "마포구");
        insertActive(HAPJEONG, "합정동", "서울특별시", "마포구");
        insert(MANGWON_RETIRED, "망원동", "서울특별시", "마포구", RETIRED_IN_2024);
        week = ReportWeek.of(clock.instant()).minusWeeks(2);
    }

    @Test
    @DisplayName("토큰 없이 발행 중인 안내를 읽는다 — 인용 수치 · 정정 시각(최근 것부터)이 있고 운영자 · 이력 내부 값과 사유는 키조차 없다")
    void anonymousReadsPublishedWithCorrections() throws Exception {
        long first = insertAdvisory(SEOGYO, "RETRACTED", "처음 안내", DAY_1, DAY_2, null);
        insertHistory(first, "PUBLISHED", "RETRACTED", SECRET_MEMO);
        long current = insertAdvisory(SEOGYO, "PUBLISHED", "서교동 기침 보고가 많이 늘었어요", DAY_2, null, first);
        insertHistory(current, "APPROVED", "PUBLISHED", null);

        String body = perform(SEOGYO, week.value(), null)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dataHeader.success").value(true))
            .andExpect(jsonPath("$.dataBody.districtCode").value(SEOGYO))
            .andExpect(jsonPath("$.dataBody.isoWeek").value(week.value()))
            .andExpect(jsonPath("$.dataBody.retraction").isEmpty())
            .andExpect(jsonPath("$.dataBody.advisory.title").value("서교동 기침 보고가 많이 늘었어요"))
            .andExpect(jsonPath("$.dataBody.advisory.body").value("손을 자주 씻어 주세요."))
            .andExpect(jsonPath("$.dataBody.advisory.publishedAt").value(instant(DAY_2)))
            .andExpect(jsonPath("$.dataBody.advisory.citation.level.code").value("HIGH"))
            .andExpect(jsonPath("$.dataBody.advisory.citation.participantCount").value(150))
            .andExpect(jsonPath("$.dataBody.advisory.citation.symptomaticCount").value(30))
            .andExpect(jsonPath("$.dataBody.advisory.citation.symptomRate").value(20.0))
            .andExpect(jsonPath("$.dataBody.advisory.citation.groups[0].group.code").value("RESPIRATORY"))
            .andExpect(jsonPath("$.dataBody.advisory.citation.groups[0].count").value(20))
            .andExpect(jsonPath("$.dataBody.advisory.citation.groups[0].rate").value(13.3))
            .andExpect(jsonPath("$.dataBody.advisory.citation.groups[1].group.code").value("ENTERIC"))
            .andExpect(jsonPath("$.dataBody.advisory.corrections.length()").value(1))
            .andExpect(jsonPath("$.dataBody.advisory.corrections[0].correctedAt").value(instant(DAY_2)))
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        JsonNode dataBody = objectMapper.readTree(body).get("dataBody");
        assertThat(fieldNames(dataBody)).containsExactlyInAnyOrder("districtCode", "isoWeek", "advisory", "retraction");
        assertThat(fieldNames(dataBody.get("advisory"))).containsExactlyInAnyOrder("title", "body", "publishedAt", "citation", "corrections");
        assertThat(fieldNames(dataBody.get("advisory").get("citation")))
            .containsExactlyInAnyOrder("level", "participantCount", "symptomaticCount", "symptomRate", "groups");
        assertThat(fieldNames(dataBody.get("advisory").get("corrections").get(0))).containsExactly("correctedAt");
        assertThat(body).doesNotContain(SECRET_MEMO).doesNotContain("처음 안내").doesNotContain(String.valueOf(current));
    }

    @Test
    @DisplayName("회원 토큰으로도 같은 공개 응답이다 — 철회만 있으면 철회 시각만 있고 내린 안내의 제목 · 본문 · 인용 수치 · 사유는 없다")
    void memberSeesRetractionOnly() throws Exception {
        long retracted = insertAdvisory(SEOGYO, "RETRACTED", "내린 안내 제목", DAY_1, DAY_3, null);
        insertHistory(retracted, "PUBLISHED", "RETRACTED", SECRET_MEMO);

        String body = perform(SEOGYO, week.value(), JwtTestTokens.bearer(ACCESS_KEY, 1L, SecurityRole.USER))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dataBody.advisory").isEmpty())
            .andExpect(jsonPath("$.dataBody.retraction.retractedAt").value(instant(DAY_3)))
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(fieldNames(objectMapper.readTree(body).get("dataBody").get("retraction"))).containsExactly("retractedAt");
        assertThat(body).doesNotContain("내린 안내 제목").doesNotContain("손을 자주").doesNotContain("participantCount").doesNotContain(SECRET_MEMO);
    }

    @Test
    @DisplayName("초안 · 수정 · 승인 단계의 안내만 있으면 안내 없음(dataBody null)이다. 안내가 하나도 없어도 같다")
    void unpublishedIsInvisible() throws Exception {
        insertAdvisory(SEOGYO, "DRAFT", "초안 제목", null, null, null);
        insertAdvisory(SEOGYO, "EDITED", "수정 제목", null, null, null);
        insertAdvisory(SEOGYO, "APPROVED", "승인 제목", null, null, null);

        perform(SEOGYO, week.value(), null)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dataHeader.success").value(true))
            .andExpect(jsonPath("$.dataBody").isEmpty());
        perform(HAPJEONG, week.value(), null)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dataBody").isEmpty());
    }

    @Test
    @DisplayName("폐지된 코드도 오류가 아니다 — 그 주에 발행된 안내가 있으면 보인다")
    void retiredDistrictIsAnswered() throws Exception {
        insertAdvisory(MANGWON_RETIRED, "PUBLISHED", "망원동 안내", DAY_1, null, null);

        perform(MANGWON_RETIRED, week.value(), null)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dataBody.advisory.title").value("망원동 안내"));
    }

    @Test
    @DisplayName("오류는 봉투로 — 코드 없음 101 · 형식 102 · 주 없음 103 · 주 형식 104 · 없는 코드 008(404) · 미래 주 009(400)")
    void errorsAreEnveloped() throws Exception {
        ReportWeek next = ReportWeek.of(clock.instant().plus(Duration.ofDays(7)));

        mockMvc.perform(get("/api/v1/advisories").param("week", week.value()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("ADVISORY_101"));
        perform("1144066", week.value(), null)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("ADVISORY_102"));
        mockMvc.perform(get("/api/v1/advisories").param("districtCode", SEOGYO))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("ADVISORY_103"));
        perform(SEOGYO, "2026-41", null)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("ADVISORY_104"));
        perform("99999999", week.value(), null)
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("ADVISORY_008"));
        perform(SEOGYO, next.value(), null)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("ADVISORY_009"));
    }

    @Test
    @DisplayName("공개 집계(홈 · 지도)의 동네 칸에 요청 주(없으면 직전 주)의 발행 안내 요약이 근거 주와 함께 붙는다 — 철회 · 승인 전은 키가 없다")
    void aggregateCarriesPublishedAdvisory() throws Exception {
        insertAdvisory(SEOGYO, "PUBLISHED", "서교동 안내", DAY_2, null, null);
        insertAdvisory(HAPJEONG, "RETRACTED", "합정동 내린 안내", DAY_1, DAY_2, null);
        insertAdvisory(HAPJEONG, "APPROVED", "합정동 승인 안내", null, null, null);

        mockMvc.perform(get("/api/v1/districts/{code}/weekly-aggregate", SEOGYO).param("week", week.value()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dataBody.district.publishedAdvisory.isoWeek").value(week.value()))
            .andExpect(jsonPath("$.dataBody.district.publishedAdvisory.title").value("서교동 안내"))
            .andExpect(jsonPath("$.dataBody.district.publishedAdvisory.publishedAt").value(instant(DAY_2)));
        mockMvc.perform(get("/api/v1/districts/weekly-aggregates").param("sigunguCode", "11440").param("week", week.value()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dataBody.districts.length()").value(2))
            .andExpect(jsonPath("$.dataBody.districts[0].code").value(SEOGYO))
            .andExpect(jsonPath("$.dataBody.districts[0].publishedAdvisory.title").value("서교동 안내"))
            .andExpect(jsonPath("$.dataBody.districts[1].code").value(HAPJEONG))
            .andExpect(jsonPath("$.dataBody.districts[1].publishedAdvisory").doesNotExist());
        // 요청 주가 안내 주의 다음 주면 직전 주 안내로 붙고, 안내 주의 전 주에는 붙지 않는다.
        mockMvc.perform(get("/api/v1/districts/{code}/weekly-aggregate", SEOGYO).param("week", ReportWeek.of(clock.instant()).previous().value()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dataBody.district.publishedAdvisory.isoWeek").value(week.value()));
        mockMvc.perform(get("/api/v1/districts/{code}/weekly-aggregate", SEOGYO).param("week", week.previous().value()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dataBody.district.publishedAdvisory").doesNotExist());
    }

    @Test
    @DisplayName("기본 호출(현재 주 — 아직 마감 전이라 안내가 없다)에는 직전 주의 발행 안내가 붙는다. 2주 전 안내는 붙지 않고, 두 주 다 있으면 요청 주다")
    void defaultWeekCarriesLastWeekAdvisory() throws Exception {
        ReportWeek current = ReportWeek.of(clock.instant());
        ReportWeek lastWeek = current.previous();
        insertAdvisory(SEOGYO, lastWeek, "PUBLISHED", "서교동 지난주 안내", DAY_3, null, null);
        // week 는 현재 주 − 2 다.
        insertAdvisory(SEOGYO, week, "PUBLISHED", "서교동 2주 전 안내", DAY_2, null, null);
        insertAdvisory(HAPJEONG, week, "PUBLISHED", "합정동 2주 전 안내", DAY_2, null, null);

        mockMvc.perform(get("/api/v1/districts/weekly-aggregates").param("sigunguCode", "11440"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dataBody.isoWeek").value(current.value()))
            .andExpect(jsonPath("$.dataBody.districts[0].code").value(SEOGYO))
            .andExpect(jsonPath("$.dataBody.districts[0].level.code").value("INSUFFICIENT"))
            .andExpect(jsonPath("$.dataBody.districts[0].publishedAdvisory.isoWeek").value(lastWeek.value()))
            .andExpect(jsonPath("$.dataBody.districts[0].publishedAdvisory.title").value("서교동 지난주 안내"))
            .andExpect(jsonPath("$.dataBody.districts[1].code").value(HAPJEONG))
            .andExpect(jsonPath("$.dataBody.districts[1].publishedAdvisory").doesNotExist());
        // 지난주를 요청하면 지난주 안내(요청 주)가 2주 전 안내(직전 주)보다 먼저다.
        mockMvc.perform(get("/api/v1/districts/{code}/weekly-aggregate", SEOGYO).param("week", lastWeek.value()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dataBody.district.publishedAdvisory.title").value("서교동 지난주 안내"));
    }

    private ResultActions perform(String districtCode, String isoWeek, String authorization) throws Exception {
        var request = get("/api/v1/advisories").param("districtCode", districtCode).param("week", isoWeek);
        if (authorization != null) {
            request.header(HttpHeaders.AUTHORIZATION, authorization);
        }
        return mockMvc.perform(request);
    }

    /** 인용 수치는 참여 150 · 증상 30 · 호흡기 20 · 장관 15 · HIGH 로 고정한다. */
    private long insertAdvisory(String districtCode, String status, String title, LocalDateTime publishedAt, LocalDateTime retractedAt, Long corrects) {
        return insertAdvisory(districtCode, week, status, title, publishedAt, retractedAt, corrects);
    }

    private long insertAdvisory(String districtCode, ReportWeek isoWeek, String status, String title, LocalDateTime publishedAt, LocalDateTime retractedAt,
        Long corrects) {
        long id = IDS.incrementAndGet();
        jdbcTemplate.update("""
                INSERT INTO advisory (id, district_code, iso_week, aggregate_id, status, title, body, cited_participant_count, cited_symptomatic_count,
                    cited_respiratory_count, cited_enteric_count, cited_level, cited_rule_version, corrects_advisory_id, published_at, retracted_at, version,
                    created_at, updated_at)
                VALUES (?, ?, ?, 1, ?, ?, '손을 자주 씻어 주세요.', 150, 30, 20, 15, 'HIGH', '2026-10-08', ?, ?, ?, 3, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)""",
            id, districtCode, isoWeek.value(), status, title, corrects, timestamp(publishedAt), timestamp(retractedAt));
        return id;
    }

    private void insertHistory(long advisoryId, String fromStatus, String toStatus, String memo) {
        jdbcTemplate.update("""
                INSERT INTO advisory_history (id, advisory_id, from_status, to_status, title, body, operator_id, memo, created_at, updated_at)
                VALUES (?, ?, ?, ?, '제목', '본문', 4242, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)""",
            IDS.incrementAndGet(), advisoryId, fromStatus, toStatus, memo);
    }

    private static Timestamp timestamp(LocalDateTime value) {
        return value == null ? null : Timestamp.valueOf(value);
    }

    /** 저장 시각은 JVM 기본 시간대의 지역 시각이다 — 응답은 같은 기준으로 되돌린 UTC 순간이다. */
    private static String instant(LocalDateTime storedAt) {
        return storedAt.atZone(ZoneId.systemDefault()).toInstant().toString();
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
