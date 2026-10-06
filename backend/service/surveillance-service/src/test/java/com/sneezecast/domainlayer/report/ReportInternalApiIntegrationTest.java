package com.sneezecast.domainlayer.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sneezecast.JwtTestTokens;
import com.sneezecast.domainlayer.district.DistrictH2TestSupport;
import com.sneezecast.domainlayer.report.application.service.ReporterKeyGenerator;
import com.sneezecast.security.common.constant.SecurityScope;
import com.sneezecast.security.common.enums.SecurityRole;
import java.util.List;
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

/**
 * 내부 파기 API 를 보안 필터 체인부터 H2 까지 실제 빈으로 부른다. auth 파기 스케줄러처럼 <b>토큰 없이</b> 부르고, 이번 주 행은 보고 API 로 만들어
 * 공개 API 와 같은 가명 키로 지우는지 본다. 지난 주 행은 JDBC 로 넣는다 (보고 API 는 현재 주만 쓴다).
 *
 * <p>회원 ID 는 다른 값과 겹치지 않는 긴 숫자로 두고, 로그에 그 숫자와 가명 키가 나오지 않는지 본다 (architecture-guide §6).
 */
@ExtendWith(OutputCaptureExtension.class)
class ReportInternalApiIntegrationTest extends DistrictH2TestSupport {

    private static final String PATH = "/internal/v1/reporters/";
    private static final long MEMBER_A = 7350912846153L;
    private static final long MEMBER_B = 7350912846154L;
    private static final String YEOKSAM_1 = "11230510";
    // 실행 시각의 이번 주와 겹치지 않도록 확실히 지난 주로 둔다.
    private static final List<String> PAST_WEEKS = List.of("2024-W52", "2025-W01", "2025-W20");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ReporterKeyGenerator reporterKeyGenerator;

    private long nextId = 1;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM weekly_report");
        clearDistricts();
        insertActive(YEOKSAM_1, "역삼1동", "서울특별시", "강남구");
    }

    @Test
    @DisplayName("토큰 없이 불러도 204 이고 대상 회원의 모든 주 행만 지운다 — 다른 회원 행은 남는다")
    void purgesEveryWeekOfMemberOnly(CapturedOutput output) throws Exception {
        seedReports(MEMBER_A);
        seedReports(MEMBER_B);
        assertThat(rowCount(MEMBER_A)).isEqualTo(4);

        mockMvc.perform(delete(PATH + MEMBER_A))
            .andExpect(status().isNoContent())
            .andExpect(content().string(""));

        assertThat(rowCount(MEMBER_A)).isZero();
        assertThat(rowCount(MEMBER_B)).isEqualTo(4);
        assertThat(totalRowCount()).isEqualTo(4);

        // 지운 건수만 남기고 회원 ID · 가명 키는 어떤 로그에도 없다. MockMvc 라 앱 코드 로그만 덮는다 — 서블릿 접근 로그나 DEBUG 웹 로그를
        // 켜면 URL 경로의 회원 ID 가 남으므로 그때 이 경로를 함께 검토한다.
        assertThat(output.getAll())
            .contains("report purge deleted rows=4")
            .doesNotContain(Long.toString(MEMBER_A))
            .doesNotContain(reporterKeyGenerator.reporterKey(MEMBER_A));
    }

    @Test
    @DisplayName("두 번 불러도 204 다 — 두 번째는 0건이다 (auth 가 완료될 때까지 다시 부른다)")
    void purgeIsIdempotent(CapturedOutput output) throws Exception {
        seedReports(MEMBER_A);

        mockMvc.perform(delete(PATH + MEMBER_A)).andExpect(status().isNoContent());
        mockMvc.perform(delete(PATH + MEMBER_A)).andExpect(status().isNoContent()).andExpect(content().string(""));

        assertThat(rowCount(MEMBER_A)).isZero();
        assertThat(output.getAll()).contains("report purge deleted rows=0");
    }

    @Test
    @DisplayName("보고가 한 번도 없던 회원도 204 다")
    void purgeWithoutReportsIsNoContent() throws Exception {
        seedReports(MEMBER_B);

        mockMvc.perform(delete(PATH + MEMBER_A)).andExpect(status().isNoContent());

        assertThat(rowCount(MEMBER_B)).isEqualTo(4);
    }

    @Test
    @DisplayName("0 · 숫자 아닌 회원 ID 는 실제 체인에서도 400 봉투이고 아무것도 지우지 않는다")
    void malformedMemberIdIsRejected() throws Exception {
        seedReports(MEMBER_A);

        mockMvc.perform(delete(PATH + "0"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("REPORT_106"));
        mockMvc.perform(delete(PATH + "abc"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("REPORT_198"));

        assertThat(rowCount(MEMBER_A)).isEqualTo(4);
    }

    @Test
    @DisplayName("유효하지 않은 Bearer 를 실으면 resource server 가 401 로 막는다 — auth 는 토큰 없이 부른다")
    void invalidBearerIsUnauthorized() throws Exception {
        seedReports(MEMBER_A);

        mockMvc.perform(delete(PATH + MEMBER_A).header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"))
            .andExpect(status().isUnauthorized());

        assertThat(rowCount(MEMBER_A)).isEqualTo(4);
    }

    /** 이번 주 1행(보고 API) + 지난 주 3행(JDBC) = 4행. */
    private void seedReports(long memberId) throws Exception {
        mockMvc.perform(put("/api/v1/reports/current")
                .header(HttpHeaders.AUTHORIZATION, JwtTestTokens.bearer(ACCESS_KEY, memberId, SecurityRole.USER, SecurityScope.REPORT_WRITE))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"districtCode\":\"" + YEOKSAM_1 + "\",\"symptomGroups\":[\"RESPIRATORY\"]}"))
            .andExpect(status().isOk());
        String reporterKey = reporterKeyGenerator.reporterKey(memberId);
        for (String isoWeek : PAST_WEEKS) {
            jdbcTemplate.update("""
                    INSERT INTO weekly_report (id, reporter_key, iso_week, district_code, symptom_mask, revision_count, created_at, updated_at)
                    VALUES (?, ?, ?, ?, 0, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)""",
                nextId++, reporterKey, isoWeek, YEOKSAM_1);
        }
    }

    private int rowCount(long memberId) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM weekly_report WHERE reporter_key = ?", Integer.class, reporterKeyGenerator.reporterKey(memberId));
        return count == null ? 0 : count;
    }

    private int totalRowCount() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM weekly_report", Integer.class);
        return count == null ? 0 : count;
    }
}
