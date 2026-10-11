package com.sneezecast.domainlayer.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sneezecast.JwtTestTokens;
import com.sneezecast.domainlayer.district.DistrictH2TestSupport;
import com.sneezecast.domainlayer.report.application.service.ReportWeekCalculator;
import com.sneezecast.domainlayer.report.application.service.ReporterKeyGenerator;
import com.sneezecast.domainlayer.report.domain.model.ReportWeek;
import com.sneezecast.domainlayer.report.domain.model.WeeklyReport;
import com.sneezecast.security.common.constant.SecurityScope;
import com.sneezecast.security.common.enums.SecurityRole;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;
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
 * 지난 보고 내역 API({@code GET /api/v1/reports/me})를 보안 필터 체인부터 H2 까지 실제 빈으로 부른다. 이번 주 보고는 실제 제출 API 로 넣고,
 * 지난 주 보고는 제출 API 로 만들 수 없으므로(서버가 주를 정한다) JDBC 로 넣는다. 이번 주는 실제 {@link ReportWeekCalculator} 로 정한다.
 *
 * <p>회원 ID 는 다른 값과 겹치지 않는 긴 숫자로 두고, 응답 · 로그에 그 숫자와 가명 키가 나오지 않는지 본다 (architecture-guide §6).
 */
@ExtendWith(OutputCaptureExtension.class)
class ReportHistoryApiIntegrationTest extends DistrictH2TestSupport {

    private static final String PATH = "/api/v1/reports/me";
    private static final long MEMBER_A = 7350912846161L;
    private static final long MEMBER_B = 7350912846162L;
    private static final String YEOKSAM_1 = "11230510";
    private static final String GARAK_1 = "11240660";
    private static final String UTC_SECONDS = "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z$";
    private static final LocalDateTime STORED_AT = LocalDateTime.of(2026, 1, 5, 9, 30, 15);

    private final AtomicLong nextId = new AtomicLong(1);

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
    }

    @Test
    @DisplayName("토큰 없이 부르면 401 SECURITY_001 봉투다")
    void anonymousIsUnauthorized() throws Exception {
        mockMvc.perform(get(PATH))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.dataHeader.success").value(false))
            .andExpect(jsonPath("$.dataHeader.resultCode").value("SECURITY_001"));
    }

    @Test
    @DisplayName("건강정보 동의 전(report:write 없는) 토큰은 지난 보고가 있어도 403 SECURITY_006 봉투다 — 민감정보")
    void tokenWithoutReportScopeIsForbidden() throws Exception {
        insertPast(MEMBER_A, reportWeekCalculator.currentWeek().previous(), YEOKSAM_1, 1);

        mockMvc.perform(get(PATH).header(HttpHeaders.AUTHORIZATION, JwtTestTokens.bearer(ACCESS_KEY, MEMBER_A, SecurityRole.USER)))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.dataHeader.success").value(false))
            .andExpect(jsonPath("$.dataHeader.resultCode").value("SECURITY_006"))
            .andExpect(jsonPath("$.dataBody").doesNotExist());
    }

    @Test
    @DisplayName("이번 주를 빼고 최근 주부터 준다 — 보관 52주(이번 주 포함) 밖의 주와 다른 회원의 보고는 나오지 않는다")
    void pastReportsExcludeCurrentWeekNewestFirst(CapturedOutput output) throws Exception {
        ReportWeek current = reportWeekCalculator.currentWeek();
        submitCurrent(MEMBER_A, GARAK_1);
        // 넣는 순서를 주 순서와 다르게 해 정렬을 DB 삽입 순서에 기대지 않는다.
        insertPast(MEMBER_A, current.minusWeeks(3), YEOKSAM_1, 2);
        insertPast(MEMBER_A, current.minusWeeks(WeeklyReport.RETENTION_WEEKS - 1), YEOKSAM_1, 0);
        insertPast(MEMBER_A, current.previous(), GARAK_1, 1);
        insertPast(MEMBER_A, current.minusWeeks(WeeklyReport.RETENTION_WEEKS), YEOKSAM_1, 3);
        insertPast(MEMBER_B, current.minusWeeks(2), GARAK_1, 3);

        String body = mockMvc.perform(get(PATH).header(HttpHeaders.AUTHORIZATION, bearer(MEMBER_A)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dataHeader.success").value(true))
            .andExpect(jsonPath("$.dataBody.hasNext").value(false))
            .andExpect(jsonPath("$.dataBody.contents.length()").value(3))
            .andExpect(jsonPath("$.dataBody.contents[0].isoWeek").value(current.previous().value()))
            .andExpect(jsonPath("$.dataBody.contents[0].districtCode").value(GARAK_1))
            .andExpect(jsonPath("$.dataBody.contents[0].symptomGroups.length()").value(1))
            .andExpect(jsonPath("$.dataBody.contents[0].symptomGroups[0].code").value("RESPIRATORY"))
            .andExpect(jsonPath("$.dataBody.contents[0].symptomGroups[0].name").value("호흡기"))
            .andExpect(jsonPath("$.dataBody.contents[0].reportedAt").value(matchesPattern(UTC_SECONDS)))
            .andExpect(jsonPath("$.dataBody.contents[0].updatedAt").value(matchesPattern(UTC_SECONDS)))
            .andExpect(jsonPath("$.dataBody.contents[1].isoWeek").value(current.minusWeeks(3).value()))
            .andExpect(jsonPath("$.dataBody.contents[1].symptomGroups[0].code").value("ENTERIC"))
            .andExpect(jsonPath("$.dataBody.contents[2].isoWeek").value(current.minusWeeks(WeeklyReport.RETENTION_WEEKS - 1).value()))
            .andExpect(jsonPath("$.dataBody.contents[2].symptomGroups.length()").value(0))
            .andExpect(jsonPath("$.dataBody.contents[0].id").doesNotExist())
            .andExpect(jsonPath("$.dataBody.contents[0].reporterKey").doesNotExist())
            .andExpect(jsonPath("$.dataBody.contents[0].revisionCount").doesNotExist())
            .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(current.value());
        String reporterKey = reporterKeyGenerator.reporterKey(MEMBER_A);
        assertThat(body).doesNotContain(Long.toString(MEMBER_A)).doesNotContain(reporterKey).doesNotContain("\"id\"");
        assertThat(output.getAll()).doesNotContain(Long.toString(MEMBER_A)).doesNotContain(reporterKey);
    }

    @Test
    @DisplayName("이번 주 보고만 있거나 보고가 없으면 빈 contents 다 — dataBody 가 null 이 아니다")
    void onlyCurrentWeekIsEmptyContents() throws Exception {
        mockMvc.perform(get(PATH).header(HttpHeaders.AUTHORIZATION, bearer(MEMBER_A)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dataBody.contents.length()").value(0))
            .andExpect(jsonPath("$.dataBody.hasNext").value(false));

        submitCurrent(MEMBER_A, YEOKSAM_1);
        insertPast(MEMBER_B, reportWeekCalculator.currentWeek().previous(), GARAK_1, 0);

        mockMvc.perform(get(PATH).header(HttpHeaders.AUTHORIZATION, bearer(MEMBER_A)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dataBody.contents").isArray())
            .andExpect(jsonPath("$.dataBody.contents.length()").value(0))
            .andExpect(jsonPath("$.dataBody.hasNext").value(false));
    }

    private void submitCurrent(long memberId, String districtCode) throws Exception {
        mockMvc.perform(put("/api/v1/reports/current").header(HttpHeaders.AUTHORIZATION, bearer(memberId))
                .contentType(MediaType.APPLICATION_JSON).content("{\"districtCode\":\"" + districtCode + "\",\"symptomGroups\":[]}"))
            .andExpect(status().isOk());
    }

    /** 지난 주 보고 한 행. 증상 마스크는 {@code SymptomGroup} 비트(RESPIRATORY 1, ENTERIC 2). */
    private void insertPast(long memberId, ReportWeek isoWeek, String districtCode, int symptomMask) {
        jdbcTemplate.update("""
                INSERT INTO weekly_report (id, reporter_key, iso_week, district_code, symptom_mask, revision_count, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 0, ?, ?)""",
            nextId.getAndIncrement(), reporterKeyGenerator.reporterKey(memberId), isoWeek.value(), districtCode, symptomMask,
            Timestamp.valueOf(STORED_AT), Timestamp.valueOf(STORED_AT));
    }

    private static String bearer(long memberId) {
        return JwtTestTokens.bearer(ACCESS_KEY, memberId, SecurityRole.USER, SecurityScope.REPORT_WRITE);
    }
}
