package com.sneezecast.domainlayer.advisory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sneezecast.JwtTestTokens;
import com.sneezecast.domainlayer.advisory.adapter.in.web.controller.AdvisoryOperatorWebController;
import com.sneezecast.domainlayer.district.DistrictH2TestSupport;
import com.sneezecast.domainlayer.report.domain.model.ReportWeek;
import com.sneezecast.security.common.enums.SecurityRole;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * 운영자 안내 API(#246)를 보안 필터 체인부터 H2 까지 실제 빈으로 부른다 — 역할 확인(비회원 401 · 일반 회원 403 · OPERATOR · ADMIN 200), 토큰
 * {@code sub} 가 이력 {@code operator_id} 로 남는지, 전이 · 충돌(409) · 입력 검증(400)이 봉투로 나오는지, 발행 · 철회가 공개 동네 안내(#215)에 어떻게
 * 보이는지 본다. 근거 집계 행은 JDBC 로 넣는다(재계산 잡 없이 마감 · 자료 부족 조합을 만든다).
 *
 * <p>근거 주는 컨텍스트의 {@code Clock} 빈(시스템 시각)으로 정한 현재 주의 2주 전이다 — 공개 동네 안내의 조회 창 안에 있다.
 */
class AdvisoryOperatorApiIntegrationTest extends DistrictH2TestSupport {

    private static final String BASE = "/api/v1/advisories/admin";
    private static final String SEOGYO = "11440660";
    private static final long OPERATOR = 9001L;
    private static final long ADMIN = 9002L;
    private static final String TITLE = "서교동 기침 보고가 많이 늘었어요";
    private static final String BODY = "손을 자주 씻어 주세요.";
    private static final String RETRACT_REASON = "중복 보고를 확인해 내림 - 운영자 메모";
    private static final String CORRECTED_TITLE = "서교동 기침 보고가 늘었어요 (정정)";
    private static final String CORRECTED_BODY = "손을 자주 씻고 기침 예절을 지켜 주세요.";
    private static final String CORRECT_REASON = "참여자 수 표기 오류 - 운영자 메모";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private Clock clock;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    private ReportWeek week;

    @BeforeEach
    void setUp() {
        clearDistricts();
        jdbcTemplate.update("DELETE FROM advisory");
        jdbcTemplate.update("DELETE FROM advisory_history");
        jdbcTemplate.update("DELETE FROM district_weekly_aggregate");
        insertActive(SEOGYO, "서교동", "서울특별시", "마포구");
        week = ReportWeek.of(clock.instant()).minusWeeks(2);
    }

    @Test
    @DisplayName("운영 API 는 OPERATOR · ADMIN 만 — 토큰 없음 401 SECURITY_001, 일반 회원(동의 scope 가 있어도) 403 SECURITY_006 이고 아무것도 남지 않는다")
    void onlyOperatorsAreAllowed() throws Exception {
        insertAggregate("HIGH", null, true);

        mockMvc.perform(json(post(BASE), draftBody(TITLE, BODY)))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("SECURITY_001"));
        mockMvc.perform(get(BASE + "/{id}", 1L))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("SECURITY_001"));
        String user = JwtTestTokens.bearer(ACCESS_KEY, 1L, SecurityRole.USER, "report:write");
        mockMvc.perform(json(post(BASE), draftBody(TITLE, BODY)).header(HttpHeaders.AUTHORIZATION, user))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("SECURITY_006"));
        mockMvc.perform(json(post(BASE + "/{id}/approve", 1L), Map.of("version", 0)).header(HttpHeaders.AUTHORIZATION, user))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("SECURITY_006"));
        // 본문 검증은 인자 해석 단계라 권한 확인보다 먼저 돈다 — 형식이 틀린 요청은 역할과 상관없이 400 이고 데이터는 읽지도 쓰지도 않는다.
        mockMvc.perform(json(post(BASE), draftBody(" ", BODY)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("ADVISORY_105"));
        assertThat(count("advisory")).isZero();

        String created = create(OPERATOR).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        long id = Long.parseLong(objectMapper.readTree(created).at("/dataBody/advisoryId").asText());
        mockMvc.perform(get(BASE + "/{id}", id).header(HttpHeaders.AUTHORIZATION, bearer(ADMIN, SecurityRole.ADMIN)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dataBody.advisory.advisoryId").value(String.valueOf(id)));
    }

    /**
     * 엔드포인트마다 유효한 요청(검증을 지나 메서드 보안까지 가는 본문)을 비회원 · 일반 회원으로 보낸다. 보호는 컨트롤러 클래스의 {@code @PreAuthorize}
     * 하나에 달려 있어(URL 수준은 열려 있다) 메서드를 더하거나 어노테이션을 옮기면 여기서 드러난다 — 목록이 컨트롤러의 핸들러 수와 같은지도 본다.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"candidates", "history", "create", "detail", "edit", "approve", "publish", "retract", "correct"})
    @DisplayName("운영 API 9개 모두 — 유효한 요청이어도 비회원 401 SECURITY_001, 일반 회원(동의 scope 포함) 403 SECURITY_006 이고 안내 · 이력이 바뀌지 않는다")
    void everyEndpointRejectsNonOperators(String endpoint) throws Exception {
        insertAggregate("HIGH", null, true);
        long id = advisoryId(create(OPERATOR));
        String user = JwtTestTokens.bearer(ACCESS_KEY, 1L, SecurityRole.USER, "report:write");

        mockMvc.perform(endpointRequest(endpoint, id))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("SECURITY_001"));
        mockMvc.perform(endpointRequest(endpoint, id).header(HttpHeaders.AUTHORIZATION, user))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("SECURITY_006"));

        assertThat(count("advisory")).isEqualTo(1);
        assertThat(count("advisory_history")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT version FROM advisory", Long.class)).isZero();
    }

    @Test
    @DisplayName("권한 회귀 테스트의 엔드포인트 목록이 운영자 컨트롤러의 핸들러 수와 같다 — 새 엔드포인트가 권한 확인 없이 빠지지 않게")
    void endpointListCoversController() {
        long handlers = handlerMapping.getHandlerMethods().values().stream()
            .filter(handler -> handler.getBeanType() == AdvisoryOperatorWebController.class)
            .count();
        assertThat(handlers).isEqualTo(9);
    }

    @Test
    @DisplayName("초안 → 수정 → 승인 → 발행 → 철회를 API 로 — 버전이 하나씩 오르고, 이력에 토큰 sub 가 운영자로 남고, 공개 안내에는 사유가 보이지 않는다")
    void lifecycleThroughApi() throws Exception {
        insertAggregate("HIGH", null, true);

        JsonNode draft = dataBody(create(OPERATOR)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dataBody.districtCode").value(SEOGYO))
            .andExpect(jsonPath("$.dataBody.isoWeek").value(week.value()))
            .andExpect(jsonPath("$.dataBody.status.code").value("DRAFT"))
            .andExpect(jsonPath("$.dataBody.status.name").value("초안"))
            .andExpect(jsonPath("$.dataBody.version").value(0))
            .andExpect(jsonPath("$.dataBody.citation.level.code").value("HIGH"))
            .andExpect(jsonPath("$.dataBody.citation.participantCount").value(150))
            .andExpect(jsonPath("$.dataBody.citation.respiratoryCount").value(20))
            .andExpect(jsonPath("$.dataBody.citation.entericCount").value(15))
            .andExpect(jsonPath("$.dataBody.citation.ruleVersion").value("2026-10-08"))
            .andExpect(jsonPath("$.dataBody.publishedAt").isEmpty()));
        assertThat(draft.get("advisoryId").isTextual()).as("ID 는 문자열이다 (coding-conventions §2-1)").isTrue();
        long id = draft.get("advisoryId").asLong();

        mockMvc.perform(json(put(BASE + "/{id}", id), Map.of("title", "고친 제목", "body", "고친 본문", "memo", "표현 다듬기", "version", 0))
                .header(HttpHeaders.AUTHORIZATION, bearer(ADMIN, SecurityRole.ADMIN)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dataBody.status.code").value("EDITED"))
            .andExpect(jsonPath("$.dataBody.version").value(1));
        transition(id, "approve", 1, OPERATOR).andExpect(status().isOk()).andExpect(jsonPath("$.dataBody.status.code").value("APPROVED"));
        String published = transition(id, "publish", 2, OPERATOR)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dataBody.status.code").value("PUBLISHED"))
            .andExpect(jsonPath("$.dataBody.version").value(3))
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String publishedAt = objectMapper.readTree(published).at("/dataBody/publishedAt").asText();
        assertThat(publishedAt).as("초 단위 UTC").matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z");

        mockMvc.perform(get("/api/v1/advisories").param("districtCode", SEOGYO).param("week", week.value()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dataBody.advisory.title").value("고친 제목"))
            .andExpect(jsonPath("$.dataBody.advisory.publishedAt").value(publishedAt));

        mockMvc.perform(json(post(BASE + "/{id}/retract", id), Map.of("reason", RETRACT_REASON, "version", 3)).header(HttpHeaders.AUTHORIZATION, bearer(ADMIN,
                SecurityRole.ADMIN)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dataBody.status.code").value("RETRACTED"))
            .andExpect(jsonPath("$.dataBody.publishedAt").value(publishedAt))
            .andExpect(jsonPath("$.dataBody.retractedAt").isNotEmpty());
        String publicBody = mockMvc.perform(get("/api/v1/advisories").param("districtCode", SEOGYO).param("week", week.value()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dataBody.advisory").isEmpty())
            .andExpect(jsonPath("$.dataBody.retraction.retractedAt").isNotEmpty())
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(publicBody).doesNotContain(RETRACT_REASON);

        JsonNode detail = dataBody(mockMvc.perform(get(BASE + "/{id}", id).header(HttpHeaders.AUTHORIZATION, bearer(OPERATOR, SecurityRole.OPERATOR)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dataBody.advisory.status.code").value("RETRACTED"))
            .andExpect(jsonPath("$.dataBody.histories.length()").value(5))
            .andExpect(jsonPath("$.dataBody.histories[0].fromStatus").isEmpty())
            .andExpect(jsonPath("$.dataBody.histories[0].toStatus.code").value("DRAFT"))
            .andExpect(jsonPath("$.dataBody.histories[1].memo").value("표현 다듬기"))
            .andExpect(jsonPath("$.dataBody.histories[1].title").value("고친 제목"))
            .andExpect(jsonPath("$.dataBody.histories[4].fromStatus.code").value("PUBLISHED"))
            .andExpect(jsonPath("$.dataBody.histories[4].toStatus.code").value("RETRACTED"))
            .andExpect(jsonPath("$.dataBody.histories[4].memo").value(RETRACT_REASON)));
        List<String> operators = new ArrayList<>();
        detail.get("histories").forEach(history -> operators.add(history.get("operatorId").asText()));
        assertThat(operators).containsExactly("9001", "9002", "9001", "9001", "9002");
        assertThat(jdbcTemplate.queryForList("SELECT operator_id FROM advisory_history ORDER BY created_at, id", Long.class))
            .containsExactly(OPERATOR, ADMIN, OPERATOR, OPERATOR, ADMIN);
    }

    @Test
    @DisplayName("충돌은 409 봉투 — 옛 버전 ADVISORY_006, 표에 없는 전이 ADVISORY_005, 같은 동네 · 주 두 번째 발행 ADVISORY_007. 없는 안내 001 · 집계 전제 002 · 003 · 004")
    void conflictsAreEnveloped() throws Exception {
        insertAggregate("HIGH", null, true);
        long first = advisoryId(create(OPERATOR));
        long second = advisoryId(create(OPERATOR));

        transition(first, "publish", 0, OPERATOR)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("ADVISORY_005"));
        transition(first, "approve", 0, OPERATOR).andExpect(status().isOk());
        transition(first, "approve", 0, OPERATOR)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("ADVISORY_006"));
        transition(first, "publish", 1, OPERATOR).andExpect(status().isOk());
        transition(second, "approve", 0, OPERATOR).andExpect(status().isOk());
        transition(second, "publish", 1, OPERATOR)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("ADVISORY_007"));
        transition(404L, "approve", 0, OPERATOR)
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("ADVISORY_001"));
        mockMvc.perform(get(BASE + "/{id}", 404L).header(HttpHeaders.AUTHORIZATION, bearer(OPERATOR, SecurityRole.OPERATOR)))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("ADVISORY_001"));
        // 거부된 요청은 이력을 남기지 않는다 — 생성 2 · 승인 2 · 발행 1 만.
        assertThat(count("advisory_history")).isEqualTo(5);

        jdbcTemplate.update("DELETE FROM district_weekly_aggregate");
        create(OPERATOR).andExpect(status().isNotFound()).andExpect(jsonPath("$.dataHeader.resultCode").value("ADVISORY_002"));
        insertAggregate("HIGH", null, false);
        create(OPERATOR).andExpect(status().isConflict()).andExpect(jsonPath("$.dataHeader.resultCode").value("ADVISORY_003"));
        jdbcTemplate.update("DELETE FROM district_weekly_aggregate");
        insertAggregate("INSUFFICIENT", "LOW_SAMPLE", true);
        create(OPERATOR).andExpect(status().isConflict()).andExpect(jsonPath("$.dataHeader.resultCode").value("ADVISORY_004"));
    }

    @Test
    @DisplayName("입력 검증은 400 봉투 — 제목 105(유니코드 공백만 포함) · 106, 본문 107 · 108, 메모 109, 사유 110 · 111, version 112 · 113, 없는 W53 010, 형식 101~104, JSON 100, ID 198")
    void requestValidation() throws Exception {
        insertAggregate("HIGH", null, true);

        assertCreateRejected(draftBody(" ", BODY), "ADVISORY_105");
        assertCreateRejected(draftBody("가".repeat(101), BODY), "ADVISORY_106");
        assertCreateRejected(draftBody(TITLE, "\n\t "), "ADVISORY_107");
        // 유니코드 공백만 — @NotBlank(trim, U+0020 이하만 공백)를 지나 도메인 isBlank 에서 500 이 나던 값이다. U+001C 는 Java 공백(isWhitespace)이지만
        // 유니코드 공백은 아니라 둘을 섞어도 거른다.
        assertCreateRejected(draftBody("　", BODY), "ADVISORY_105");
        assertCreateRejected(draftBody("     ", BODY), "ADVISORY_105");
        assertCreateRejected(draftBody("\u001C　", BODY), "ADVISORY_105");
        assertCreateRejected(draftBody(TITLE, "　  "), "ADVISORY_107");
        assertCreateRejected(draftBody(TITLE, "가".repeat(16_384)), "ADVISORY_108");
        assertCreateRejected(Map.of("isoWeek", week.value(), "title", TITLE, "body", BODY), "ADVISORY_101");
        assertCreateRejected(Map.of("districtCode", "1144066", "isoWeek", week.value(), "title", TITLE, "body", BODY), "ADVISORY_102");
        assertCreateRejected(Map.of("districtCode", SEOGYO, "title", TITLE, "body", BODY), "ADVISORY_103");
        assertCreateRejected(Map.of("districtCode", SEOGYO, "isoWeek", "2026-41", "title", TITLE, "body", BODY), "ADVISORY_104");
        // 2027 년에는 53주가 없다 — 형식은 맞지만 없는 주다.
        assertCreateRejected(Map.of("districtCode", SEOGYO, "isoWeek", "2027-W53", "title", TITLE, "body", BODY), "ADVISORY_010");
        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content("{not json").header(HttpHeaders.AUTHORIZATION, operator()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("ADVISORY_100"));
        assertThat(count("advisory")).isZero();

        // 상한 그대로는 받는다 — 제목 100자, 본문 16,383자(TEXT 65,535 바이트 ÷ utf8mb4 4바이트).
        long id = advisoryId(mockMvc.perform(json(post(BASE), draftBody("가".repeat(100), "가".repeat(16_383))).header(HttpHeaders.AUTHORIZATION, operator()))
            .andExpect(status().isOk()));

        Map<String, Object> memoTooLong = new HashMap<>(Map.of("title", TITLE, "body", BODY, "memo", "가".repeat(501), "version", 0));
        assertRejected(json(put(BASE + "/{id}", id), memoTooLong), "ADVISORY_109");
        assertRejected(json(put(BASE + "/{id}", id), Map.of("title", TITLE, "body", BODY)), "ADVISORY_112");
        assertRejected(json(post(BASE + "/{id}/approve", id), Map.of("version", -1)), "ADVISORY_113");
        assertRejected(json(post(BASE + "/{id}/approve", id), Map.of()), "ADVISORY_112");
        assertRejected(json(post(BASE + "/{id}/approve", id), Map.of("version", "abc")), "ADVISORY_100");
        assertRejected(json(post(BASE + "/{id}/retract", id), Map.of("reason", "  ", "version", 0)), "ADVISORY_110");
        assertRejected(json(post(BASE + "/{id}/retract", id), Map.of("version", 0)), "ADVISORY_110");
        assertRejected(json(post(BASE + "/{id}/retract", id), Map.of("reason", "　", "version", 0)), "ADVISORY_110");
        assertRejected(json(put(BASE + "/{id}", id), Map.of("title", "　", "body", BODY, "version", 0)), "ADVISORY_105");
        assertRejected(json(put(BASE + "/{id}", id), Map.of("title", TITLE, "body", "　\n", "version", 0)), "ADVISORY_107");
        assertRejected(json(post(BASE + "/{id}/retract", id), Map.of("reason", "가".repeat(501), "version", 0)), "ADVISORY_111");
        assertRejected(json(post(BASE + "/{id}/approve", "abc"), Map.of("version", 0)), "ADVISORY_198");
        // 거부된 요청은 안내 · 이력을 바꾸지 않는다.
        assertThat(jdbcTemplate.queryForObject("SELECT version FROM advisory", Long.class)).isZero();
        assertThat(count("advisory_history")).isEqualTo(1);
    }

    @Test
    @DisplayName("정정 발행을 API 로 — 새 안내가 발행되고, 공개 동네 안내에는 새 안내와 정정 시각만(철회 표시 · 사유 · 이전 안내 없음), 발행 이력에는 두 안내가 양방향으로 이어진다")
    void correctionThroughApi() throws Exception {
        insertAggregate("HIGH", null, true);
        long id = advisoryId(create(OPERATOR));
        transition(id, "approve", 0, OPERATOR).andExpect(status().isOk());
        transition(id, "publish", 1, OPERATOR).andExpect(status().isOk());

        JsonNode correction = dataBody(mockMvc.perform(json(post(BASE + "/{id}/correct", id), correctBody(2))
                .header(HttpHeaders.AUTHORIZATION, bearer(ADMIN, SecurityRole.ADMIN)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dataBody.status.code").value("PUBLISHED"))
            .andExpect(jsonPath("$.dataBody.correctsAdvisoryId").value(String.valueOf(id)))
            .andExpect(jsonPath("$.dataBody.title").value(CORRECTED_TITLE))
            .andExpect(jsonPath("$.dataBody.citation.participantCount").value(150))
            .andExpect(jsonPath("$.dataBody.version").value(1)));
        long correctionId = correction.get("advisoryId").asLong();
        String correctedAt = correction.get("publishedAt").asText();

        String publicBody = mockMvc.perform(get("/api/v1/advisories").param("districtCode", SEOGYO).param("week", week.value()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dataBody.advisory.title").value(CORRECTED_TITLE))
            .andExpect(jsonPath("$.dataBody.advisory.body").value(CORRECTED_BODY))
            .andExpect(jsonPath("$.dataBody.advisory.publishedAt").value(correctedAt))
            .andExpect(jsonPath("$.dataBody.advisory.corrections.length()").value(1))
            .andExpect(jsonPath("$.dataBody.advisory.corrections[0].correctedAt").value(correctedAt))
            .andExpect(jsonPath("$.dataBody.retraction").isEmpty())
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(publicBody).doesNotContain(CORRECT_REASON).doesNotContain(String.valueOf(id)).doesNotContain(TITLE);

        mockMvc.perform(get(BASE + "/history").param("week", week.value()).header(HttpHeaders.AUTHORIZATION, operator()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dataBody.entries.length()").value(2))
            .andExpect(jsonPath("$.dataBody.entries[0].advisory.advisoryId").value(String.valueOf(correctionId)))
            .andExpect(jsonPath("$.dataBody.entries[0].advisory.correctsAdvisoryId").value(String.valueOf(id)))
            .andExpect(jsonPath("$.dataBody.entries[0].correctedByAdvisoryId").isEmpty())
            .andExpect(jsonPath("$.dataBody.entries[0].districtName").value("서교동"))
            .andExpect(jsonPath("$.dataBody.entries[0].sigungu").value("서울특별시 마포구"))
            .andExpect(jsonPath("$.dataBody.entries[0].histories.length()").value(2))
            .andExpect(jsonPath("$.dataBody.entries[0].histories[0].fromStatus").isEmpty())
            .andExpect(jsonPath("$.dataBody.entries[0].histories[0].toStatus.code").value("APPROVED"))
            .andExpect(jsonPath("$.dataBody.entries[0].histories[0].memo").value(CORRECT_REASON))
            .andExpect(jsonPath("$.dataBody.entries[0].histories[1].toStatus.code").value("PUBLISHED"))
            .andExpect(jsonPath("$.dataBody.entries[0].histories[1].operatorId").value(String.valueOf(ADMIN)))
            .andExpect(jsonPath("$.dataBody.entries[1].advisory.advisoryId").value(String.valueOf(id)))
            .andExpect(jsonPath("$.dataBody.entries[1].advisory.status.code").value("RETRACTED"))
            .andExpect(jsonPath("$.dataBody.entries[1].advisory.retractedAt").value(correctedAt))
            .andExpect(jsonPath("$.dataBody.entries[1].correctedByAdvisoryId").value(String.valueOf(correctionId)))
            .andExpect(jsonPath("$.dataBody.entries[1].histories.length()").value(4))
            .andExpect(jsonPath("$.dataBody.entries[1].histories[3].toStatus.code").value("RETRACTED"))
            .andExpect(jsonPath("$.dataBody.entries[1].histories[3].memo").value(CORRECT_REASON))
            .andExpect(jsonPath("$.dataBody.entries[1].histories[3].operatorId").value(String.valueOf(ADMIN)));
    }

    @Test
    @DisplayName("발행 이력은 끝 주로 끝나는 4주의 발행됨 · 철회됨만 — 초안 · 승인 단계와 범위 밖 주는 빠지고, 생략하면 현재 주 − 1 로 끝난다")
    void publicationHistoryRange() throws Exception {
        insertAggregate("HIGH", null, true);
        long published = advisoryId(create(OPERATOR));
        transition(published, "approve", 0, OPERATOR).andExpect(status().isOk());
        transition(published, "publish", 1, OPERATOR).andExpect(status().isOk());
        long approved = advisoryId(create(OPERATOR));
        transition(approved, "approve", 0, OPERATOR).andExpect(status().isOk());
        create(OPERATOR).andExpect(status().isOk());
        // 범위 첫 주(끝 주 − 3)의 철회된 안내는 들어오고, 그 앞 주(끝 주 − 4)는 빠진다. 이력 행이 없으면 빈 목록이다.
        insertPublicAdvisory(7001L, week.minusWeeks(3), "RETRACTED", LocalDateTime.of(2026, 1, 5, 10, 0), LocalDateTime.of(2026, 1, 6, 10, 0));
        insertPublicAdvisory(7002L, week.minusWeeks(4), "PUBLISHED", LocalDateTime.of(2026, 1, 1, 10, 0), null);

        mockMvc.perform(get(BASE + "/history").param("week", week.value()).header(HttpHeaders.AUTHORIZATION, operator()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dataBody.fromIsoWeek").value(week.minusWeeks(3).value()))
            .andExpect(jsonPath("$.dataBody.toIsoWeek").value(week.value()))
            .andExpect(jsonPath("$.dataBody.entries.length()").value(2))
            .andExpect(jsonPath("$.dataBody.entries[0].advisory.advisoryId").value(String.valueOf(published)))
            .andExpect(jsonPath("$.dataBody.entries[0].advisory.status.code").value("PUBLISHED"))
            .andExpect(jsonPath("$.dataBody.entries[0].histories.length()").value(3))
            .andExpect(jsonPath("$.dataBody.entries[1].advisory.advisoryId").value("7001"))
            .andExpect(jsonPath("$.dataBody.entries[1].advisory.status.code").value("RETRACTED"))
            .andExpect(jsonPath("$.dataBody.entries[1].correctedByAdvisoryId").isEmpty())
            .andExpect(jsonPath("$.dataBody.entries[1].histories.length()").value(0));

        // 생략하면 현재 주 − 1 로 끝난다 — 근거 주(현재 주 − 2)가 그 안이다.
        ReportWeek current = ReportWeek.of(clock.instant());
        mockMvc.perform(get(BASE + "/history").header(HttpHeaders.AUTHORIZATION, operator()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dataBody.toIsoWeek").value(current.previous().value()))
            .andExpect(jsonPath("$.dataBody.fromIsoWeek").value(current.minusWeeks(4).value()))
            .andExpect(jsonPath("$.dataBody.entries.length()").value(1));
    }

    @Test
    @DisplayName("정정 · 발행 이력 입력 검증은 400 봉투 — 제목 105 · 106, 본문 107 · 108, 사유 110 · 111, version 112 · 113, 주 형식 104 · 창 밖 009. 발행 중이 아니면 005")
    void correctionAndHistoryValidation() throws Exception {
        insertAggregate("HIGH", null, true);
        long id = advisoryId(create(OPERATOR));

        assertRejected(json(post(BASE + "/{id}/correct", id), Map.of("title", "　", "body", BODY, "reason", CORRECT_REASON, "version", 0)), "ADVISORY_105");
        assertRejected(json(post(BASE + "/{id}/correct", id), Map.of("title", "가".repeat(101), "body", BODY, "reason", CORRECT_REASON, "version", 0)),
            "ADVISORY_106");
        assertRejected(json(post(BASE + "/{id}/correct", id), Map.of("title", TITLE, "reason", CORRECT_REASON, "version", 0)), "ADVISORY_107");
        assertRejected(json(post(BASE + "/{id}/correct", id), Map.of("title", TITLE, "body", "가".repeat(16_384), "reason", CORRECT_REASON, "version", 0)),
            "ADVISORY_108");
        assertRejected(json(post(BASE + "/{id}/correct", id), Map.of("title", TITLE, "body", BODY, "reason", " ", "version", 0)), "ADVISORY_110");
        assertRejected(json(post(BASE + "/{id}/correct", id), Map.of("title", TITLE, "body", BODY, "version", 0)), "ADVISORY_110");
        assertRejected(json(post(BASE + "/{id}/correct", id), Map.of("title", TITLE, "body", BODY, "reason", "가".repeat(501), "version", 0)), "ADVISORY_111");
        assertRejected(json(post(BASE + "/{id}/correct", id), Map.of("title", TITLE, "body", BODY, "reason", CORRECT_REASON)), "ADVISORY_112");
        assertRejected(json(post(BASE + "/{id}/correct", id), correctBody(-1)), "ADVISORY_113");
        assertRejected(json(post(BASE + "/{id}/correct", "abc"), correctBody(0)), "ADVISORY_198");
        assertRejected(get(BASE + "/history").param("week", "2026-41"), "ADVISORY_104");
        assertRejected(get(BASE + "/history").param("week", ReportWeek.of(clock.instant().plus(Duration.ofDays(7))).value()), "ADVISORY_009");

        // 초안은 정정할 수 없다(발행됨에서만) — 409 이고 아무것도 바뀌지 않는다.
        mockMvc.perform(json(post(BASE + "/{id}/correct", id), correctBody(0)).header(HttpHeaders.AUTHORIZATION, operator()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("ADVISORY_005"));
        assertThat(count("advisory")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT version FROM advisory", Long.class)).isZero();
        assertThat(count("advisory_history")).isEqualTo(1);
    }

    private static Map<String, Object> correctBody(long version) {
        return Map.of("title", CORRECTED_TITLE, "body", CORRECTED_BODY, "reason", CORRECT_REASON, "version", version);
    }

    /** 발행됨 · 철회됨 안내 한 행을 JDBC 로 — 근거 집계 없이 다른 주의 안내를 만든다. */
    private void insertPublicAdvisory(long id, ReportWeek isoWeek, String status, LocalDateTime publishedAt, LocalDateTime retractedAt) {
        jdbcTemplate.update("""
                INSERT INTO advisory (id, district_code, iso_week, aggregate_id, status, title, body, cited_participant_count, cited_symptomatic_count,
                    cited_respiratory_count, cited_enteric_count, cited_level, cited_rule_version, corrects_advisory_id, published_at, retracted_at, version,
                    created_at, updated_at)
                VALUES (?, ?, ?, 1, ?, '다른 주 안내', '본문', 150, 30, 20, 15, 'HIGH', '2026-10-08', NULL, ?, ?, 3, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)""",
            id, SEOGYO, isoWeek.value(), status, Timestamp.valueOf(publishedAt), retractedAt == null ? null : Timestamp.valueOf(retractedAt));
    }

    private void assertCreateRejected(Map<String, ?> body, String resultCode) throws Exception {
        assertRejected(json(post(BASE), body), resultCode);
    }

    private void assertRejected(MockHttpServletRequestBuilder request, String resultCode) throws Exception {
        mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION, operator()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.dataHeader.success").value(false))
            .andExpect(jsonPath("$.dataHeader.resultCode").value(resultCode));
    }

    /** 권한 회귀 테스트용 — 엔드포인트마다 검증을 지나는 요청. */
    private MockHttpServletRequestBuilder endpointRequest(String endpoint, long id) throws Exception {
        return switch (endpoint) {
            case "candidates" -> get(BASE + "/candidates").param("week", week.value());
            case "create" -> json(post(BASE), draftBody(TITLE, BODY));
            case "detail" -> get(BASE + "/{id}", id);
            case "edit" -> json(put(BASE + "/{id}", id), Map.of("title", TITLE, "body", BODY, "version", 0));
            case "approve", "publish" -> json(post(BASE + "/{id}/" + endpoint, id), Map.of("version", 0));
            case "retract" -> json(post(BASE + "/{id}/retract", id), Map.of("reason", RETRACT_REASON, "version", 0));
            case "correct" -> json(post(BASE + "/{id}/correct", id), correctBody(0));
            case "history" -> get(BASE + "/history").param("week", week.value());
            default -> throw new IllegalArgumentException("모르는 엔드포인트: " + endpoint);
        };
    }

    private ResultActions create(long operatorId) throws Exception {
        return mockMvc.perform(json(post(BASE), draftBody(TITLE, BODY)).header(HttpHeaders.AUTHORIZATION, bearer(operatorId, SecurityRole.OPERATOR)));
    }

    private ResultActions transition(long id, String action, long version, long operatorId) throws Exception {
        return mockMvc.perform(json(post(BASE + "/{id}/" + action, id), Map.of("version", version))
            .header(HttpHeaders.AUTHORIZATION, bearer(operatorId, SecurityRole.OPERATOR)));
    }

    private Map<String, Object> draftBody(String title, String body) {
        return Map.of("districtCode", SEOGYO, "isoWeek", week.value(), "title", title, "body", body);
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, Map<String, ?> body) throws Exception {
        return request.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
    }

    private JsonNode dataBody(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)).get("dataBody");
    }

    private long advisoryId(ResultActions result) throws Exception {
        return dataBody(result).get("advisoryId").asLong();
    }

    private static String operator() {
        return bearer(OPERATOR, SecurityRole.OPERATOR);
    }

    private static String bearer(long memberId, SecurityRole role) {
        return JwtTestTokens.bearer(ACCESS_KEY, memberId, role);
    }

    private int count(String table) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return count == null ? 0 : count;
    }

    /** 서교동 · 근거 주 집계 한 행 (참여 150 · 증상 30 · 호흡기 20 · 장관 15). 자료 부족이면 기준선 없이 이유만 둔다. */
    private void insertAggregate(String level, String insufficientReason, boolean finalized) {
        boolean insufficient = insufficientReason != null;
        Timestamp at = Timestamp.valueOf(LocalDateTime.of(2026, 10, 5, 0, 10));
        jdbcTemplate.update("""
                INSERT INTO district_weekly_aggregate (id, district_code, iso_week, participant_count, symptomatic_count, respiratory_count, enteric_count,
                  revised_report_count, level, insufficient_reason, baseline_participant_count, baseline_symptomatic_count, rule_version, calculated_at,
                  finalized_at, created_at, updated_at)
                VALUES (100, ?, ?, 150, 30, 20, 15, 2, ?, ?, ?, ?, '2026-10-08', ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)""",
            SEOGYO, week.value(), level, insufficientReason, insufficient ? null : 600, insufficient ? null : 60, at, finalized ? at : null);
    }
}
