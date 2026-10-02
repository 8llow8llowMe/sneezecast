package com.sneezecast.domainlayer.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sneezecast.JwtTestTokens;
import com.sneezecast.domainlayer.district.DistrictH2TestSupport;
import com.sneezecast.domainlayer.report.adapter.out.persistence.entity.WeeklyReportEntity;
import com.sneezecast.domainlayer.report.application.service.ReportWeekCalculator;
import com.sneezecast.domainlayer.report.application.service.ReporterKeyGenerator;
import com.sneezecast.security.common.constant.SecurityScope;
import com.sneezecast.security.common.enums.SecurityRole;
import java.sql.Timestamp;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 보고 API 를 보안 필터 체인부터 H2 까지 실제 빈으로 부른다. 토큰은 auth 와 같은 발급 코드로 만든다. 행정동 행은 batch 처럼 JDBC 로 넣는다.
 *
 * <p>회원 ID 는 일부러 다른 값과 겹치지 않는 긴 숫자로 두고, 응답 · DB 행 · 로그 어디에도 그 숫자(와 가명 키)가 나오지 않는지 본다
 * (architecture-guide §6). {@code weekly_report} 의 컬럼 목록은 {@code WeeklyReportEntitySchemaTest} 가 고정한다.
 */
@ExtendWith(OutputCaptureExtension.class)
class ReportApiIntegrationTest extends DistrictH2TestSupport {

    private static final String PATH = "/api/v1/reports/current";
    private static final long MEMBER_A = 7350912846153L;
    private static final long MEMBER_B = 7350912846154L;
    private static final String YEOKSAM_1 = "11230510";
    private static final String GARAK_1 = "11240660";
    private static final String RETIRED = "21120560";
    private static final String UTC_SECONDS = "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z$";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ReporterKeyGenerator reporterKeyGenerator;

    @Autowired
    private ReportWeekCalculator reportWeekCalculator;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM weekly_report");
        clearDistricts();
        insertActive(YEOKSAM_1, "역삼1동", "서울특별시", "강남구");
        insertActive(GARAK_1, "가락1동", "서울특별시", "송파구");
        insert(RETIRED, "녹산동", "부산광역시", "강서구", RETIRED_IN_2024);
    }

    @Test
    @DisplayName("토큰 없이 부르면 세 API 모두 401 SECURITY_001 봉투다")
    void anonymousIsUnauthorized() throws Exception {
        for (ResultActions result : List.of(
            mockMvc.perform(put(PATH).contentType(MediaType.APPLICATION_JSON).content(body(YEOKSAM_1))),
            mockMvc.perform(get(PATH)),
            mockMvc.perform(delete(PATH)))) {
            result.andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.dataHeader.success").value(false))
                .andExpect(jsonPath("$.dataHeader.resultCode").value("SECURITY_001"));
        }
        assertThat(rowCount()).isZero();
    }

    @Test
    @DisplayName("건강정보 동의 전(report:write 없는) 토큰은 조회까지 403 SECURITY_006 봉투다 — 민감정보")
    void tokenWithoutReportScopeIsForbidden() throws Exception {
        String token = JwtTestTokens.bearer(ACCESS_KEY, MEMBER_A, SecurityRole.USER);
        for (ResultActions result : List.of(
            mockMvc.perform(put(PATH).header(HttpHeaders.AUTHORIZATION, token).contentType(MediaType.APPLICATION_JSON).content(body(YEOKSAM_1))),
            mockMvc.perform(get(PATH).header(HttpHeaders.AUTHORIZATION, token)),
            mockMvc.perform(delete(PATH).header(HttpHeaders.AUTHORIZATION, token)))) {
            result.andExpect(status().isForbidden())
                .andExpect(jsonPath("$.dataHeader.success").value(false))
                .andExpect(jsonPath("$.dataHeader.resultCode").value("SECURITY_006"));
        }
        assertThat(rowCount()).isZero();
    }

    @Test
    @DisplayName("제출 → 수정 → 조회 → 취소 → 조회(null) → 취소(멱등) — 같은 주는 한 행이고 수정 횟수는 1 이다")
    void submitUpdateGetCancelFlow(CapturedOutput output) throws Exception {
        String isoWeek = reportWeekCalculator.currentWeek().value();

        String created = submit(MEMBER_A, body(YEOKSAM_1))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dataHeader.success").value(true))
            .andExpect(jsonPath("$.dataBody.isoWeek").value(isoWeek))
            .andExpect(jsonPath("$.dataBody.districtCode").value(YEOKSAM_1))
            .andExpect(jsonPath("$.dataBody.symptomGroups.length()").value(0))
            .andExpect(jsonPath("$.dataBody.reportedAt").value(matchesPattern(UTC_SECONDS)))
            .andExpect(jsonPath("$.dataBody.updatedAt").value(matchesPattern(UTC_SECONDS)))
            .andReturn().getResponse().getContentAsString();

        String updated = submit(MEMBER_A, body(GARAK_1, "ENTERIC", "RESPIRATORY"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dataBody.districtCode").value(GARAK_1))
            .andExpect(jsonPath("$.dataBody.symptomGroups[0].code").value("RESPIRATORY"))
            .andExpect(jsonPath("$.dataBody.symptomGroups[0].name").value("호흡기"))
            .andExpect(jsonPath("$.dataBody.symptomGroups[1].code").value("ENTERIC"))
            .andExpect(jsonPath("$.dataBody.revisionCount").doesNotExist())
            .andReturn().getResponse().getContentAsString();

        assertThat(rowCount()).isEqualTo(1);
        Map<String, Object> row = row(MEMBER_A);
        assertThat(row).containsEntry("ISO_WEEK", isoWeek).containsEntry("DISTRICT_CODE", GARAK_1);
        assertThat(number(row, "REVISION_COUNT")).isEqualTo(1);
        assertThat(number(row, "SYMPTOM_MASK")).isEqualTo(3);

        String fetched = mockMvc.perform(get(PATH).header(HttpHeaders.AUTHORIZATION, bearer(MEMBER_A)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dataBody.districtCode").value(GARAK_1))
            .andExpect(jsonPath("$.dataBody.symptomGroups.length()").value(2))
            // 저장 시각(JVM 기본 시간대의 지역 시각)을 같은 기준으로 읽은 초 단위 순간이다.
            .andExpect(jsonPath("$.dataBody.reportedAt").value(utcSeconds((Timestamp) row.get("CREATED_AT"))))
            .andExpect(jsonPath("$.dataBody.updatedAt").value(utcSeconds((Timestamp) row.get("UPDATED_AT"))))
            .andReturn().getResponse().getContentAsString();

        mockMvc.perform(delete(PATH).header(HttpHeaders.AUTHORIZATION, bearer(MEMBER_A)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dataHeader.success").value(true));
        assertThat(rowCount()).isZero();

        mockMvc.perform(get(PATH).header(HttpHeaders.AUTHORIZATION, bearer(MEMBER_A)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dataHeader.success").value(true))
            .andExpect(jsonPath("$.dataBody").isEmpty());
        mockMvc.perform(delete(PATH).header(HttpHeaders.AUTHORIZATION, bearer(MEMBER_A)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dataHeader.success").value(true));

        // 응답 · 로그 어디에도 회원 ID 와 가명 키가 나오지 않는다.
        String reporterKey = reporterKeyGenerator.reporterKey(MEMBER_A);
        for (String response : List.of(created, updated, fetched)) {
            assertThat(response).doesNotContain(Long.toString(MEMBER_A)).doesNotContain(reporterKey).doesNotContain("\"id\"");
        }
        assertThat(output.getAll()).doesNotContain(Long.toString(MEMBER_A)).doesNotContain(reporterKey);
    }

    @Test
    @DisplayName("DB 행에는 회원 ID 가 어느 컬럼에도 없고 가명 키만 있다")
    void storedRowHasNoMemberId() throws Exception {
        submit(MEMBER_A, body(YEOKSAM_1, "RESPIRATORY")).andExpect(status().isOk());

        Map<String, Object> row = row(MEMBER_A);
        assertThat(row.get("REPORTER_KEY")).isEqualTo(reporterKeyGenerator.reporterKey(MEMBER_A));
        row.forEach((column, value) -> assertThat(String.valueOf(value)).as(column).doesNotContain(Long.toString(MEMBER_A)));
    }

    @Test
    @DisplayName("다른 회원의 보고와 섞이지 않는다 — 조회 · 수정 · 취소가 각자 행만 본다")
    void reportsAreScopedToMember() throws Exception {
        submit(MEMBER_A, body(YEOKSAM_1, "RESPIRATORY")).andExpect(status().isOk());

        mockMvc.perform(get(PATH).header(HttpHeaders.AUTHORIZATION, bearer(MEMBER_B)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dataBody").isEmpty());

        submit(MEMBER_B, body(GARAK_1)).andExpect(status().isOk());
        mockMvc.perform(delete(PATH).header(HttpHeaders.AUTHORIZATION, bearer(MEMBER_B))).andExpect(status().isOk());

        mockMvc.perform(get(PATH).header(HttpHeaders.AUTHORIZATION, bearer(MEMBER_A)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dataBody.districtCode").value(YEOKSAM_1))
            .andExpect(jsonPath("$.dataBody.symptomGroups[0].code").value("RESPIRATORY"));
        assertThat(rowCount()).isEqualTo(1);
        assertThat(number(row(MEMBER_A), "REVISION_COUNT")).isZero();
    }

    @Test
    @DisplayName("없는 행정동은 400 REPORT_002, 폐지 행정동은 400 REPORT_003 이고 저장하지 않는다")
    void unknownOrRetiredDistrictIsRejected() throws Exception {
        submit(MEMBER_A, body("99999999"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.dataHeader.success").value(false))
            .andExpect(jsonPath("$.dataHeader.resultCode").value("REPORT_002"));
        submit(MEMBER_A, body(RETIRED))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("REPORT_003"));
        assertThat(rowCount()).isZero();

        // 이미 있는 보고를 폐지 행정동으로 고치려 해도 거부하고 기존 행은 그대로다.
        submit(MEMBER_A, body(YEOKSAM_1)).andExpect(status().isOk());
        submit(MEMBER_A, body(RETIRED)).andExpect(jsonPath("$.dataHeader.resultCode").value("REPORT_003"));
        assertThat(row(MEMBER_A)).containsEntry("DISTRICT_CODE", YEOKSAM_1);
        assertThat(number(row(MEMBER_A), "REVISION_COUNT")).isZero();
    }

    @Test
    @DisplayName("실제 ObjectMapper 에서도 알 수 없는 증상군은 REPORT_100 이고 허용 값을 알려 준다")
    void unknownSymptomGroupIsRejected() throws Exception {
        submit(MEMBER_A, body(YEOKSAM_1, "FEVER"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("REPORT_100"))
            .andExpect(jsonPath("$.dataHeader.fieldErrors[0].field").value("symptomGroups[0]"))
            .andExpect(jsonPath("$.dataHeader.resultMessage").value(containsString("RESPIRATORY, ENTERIC")));
        assertThat(rowCount()).isZero();
    }

    @Test
    @DisplayName("수정 횟수가 SMALLINT 상한이어도 수정은 200 이고 값은 상한에 머문다")
    void revisionCountSaturates() throws Exception {
        submit(MEMBER_A, body(YEOKSAM_1)).andExpect(status().isOk());
        jdbcTemplate.update("UPDATE weekly_report SET revision_count = ?", WeeklyReportEntity.MAX_REVISION_COUNT);

        submit(MEMBER_A, body(GARAK_1, "ENTERIC"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dataBody.districtCode").value(GARAK_1));

        assertThat(number(row(MEMBER_A), "REVISION_COUNT")).isEqualTo(32767);
    }

    private ResultActions submit(long memberId, String body) throws Exception {
        return mockMvc.perform(put(PATH).header(HttpHeaders.AUTHORIZATION, bearer(memberId)).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static String bearer(long memberId) {
        return JwtTestTokens.bearer(ACCESS_KEY, memberId, SecurityRole.USER, SecurityScope.REPORT_WRITE);
    }

    private static String body(String districtCode, String... symptomGroups) {
        String groups = String.join(",", Arrays.stream(symptomGroups).map(group -> "\"" + group + "\"").toList());
        return "{\"districtCode\":\"" + districtCode + "\",\"symptomGroups\":[" + groups + "]}";
    }

    private static String utcSeconds(Timestamp storedAt) {
        return storedAt.toLocalDateTime().atZone(ZoneId.systemDefault()).toInstant().truncatedTo(ChronoUnit.SECONDS).toString();
    }

    private int rowCount() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM weekly_report", Integer.class);
        return count == null ? 0 : count;
    }

    private Map<String, Object> row(long memberId) {
        return jdbcTemplate.queryForMap("SELECT * FROM weekly_report WHERE reporter_key = ?", reporterKeyGenerator.reporterKey(memberId));
    }

    // H2 는 TINYINT · SMALLINT 를 드라이버 버전에 따라 Byte · Short · Integer 로 줄 수 있어 숫자로만 비교한다.
    private static int number(Map<String, Object> row, String column) {
        return ((Number) row.get(column)).intValue();
    }
}
