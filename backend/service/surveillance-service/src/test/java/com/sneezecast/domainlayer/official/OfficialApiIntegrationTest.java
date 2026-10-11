package com.sneezecast.domainlayer.official;

import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sneezecast.JwtTestTokens;
import com.sneezecast.domainlayer.official.domain.enums.OfficialAgeGroup;
import com.sneezecast.domainlayer.official.domain.enums.OfficialMetric;
import com.sneezecast.domainlayer.official.domain.enums.OfficialProgram;
import com.sneezecast.security.common.enums.SecurityRole;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 공식 정보 API({@code GET /api/v1/official/latest})를 보안 필터 체인부터 H2 까지 실제 빈으로 부른다. 비회원 둘러보기에서도 보므로 토큰 없이 통과해야
 * 한다. 지금 주는 실제 {@link Clock} 빈(KST)으로 정한다 — 전수신고 표시 지연(기본 2주)을 실제 설정값으로 본다.
 */
class OfficialApiIntegrationTest extends OfficialH2TestSupport {

    private static final String PATH = "/api/v1/official/latest";
    private static final String UTC_SECONDS = "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z$";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private Clock clock;

    private LocalDate currentWeekStart;

    @BeforeEach
    void setUp() {
        clearOfficial();
        currentWeekStart = LocalDate.now(clock).with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY));
    }

    @Test
    @DisplayName("자료가 없으면 토큰 없이도 200 이고 programs 가 빈 배열이다 — dataBody 는 null 이 아니다")
    void emptyIsOkWithEmptyPrograms() throws Exception {
        mockMvc.perform(get(PATH))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dataHeader.success").value(true))
            .andExpect(jsonPath("$.dataBody.programs").isArray())
            .andExpect(jsonPath("$.dataBody.programs.length()").value(0));
    }

    @Test
    @DisplayName("토큰 없이 200 이고 출처 · 집계 단위 · 기준 주 · 적재 시각 · 원문 주소를 함께 싣는다")
    void anonymousGetsSourceUnitPeriodAndSyncTime() throws Exception {
        LocalDate lastWeek = currentWeekStart.minusWeeks(1);
        insertWeekly(OfficialProgram.ARI, "ND0704", currentWeekStart.minusWeeks(2), new BigDecimal("140"));
        insertWeekly(OfficialProgram.ARI, "ND0704", lastWeek, new BigDecimal("152"));

        mockMvc.perform(get(PATH))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dataBody.programs.length()").value(1))
            .andExpect(jsonPath("$.dataBody.programs[0].program.code").value("ARI"))
            .andExpect(jsonPath("$.dataBody.programs[0].program.name").value("급성호흡기감염증"))
            .andExpect(jsonPath("$.dataBody.programs[0].source.code").value("KDCA_SENTINEL"))
            .andExpect(jsonPath("$.dataBody.programs[0].source.name").value("질병관리청 표본감시"))
            .andExpect(jsonPath("$.dataBody.programs[0].sourceUrl").value("https://dportal.kdca.go.kr/pot/is/st/ari.do"))
            .andExpect(jsonPath("$.dataBody.programs[0].diseases.length()").value(1))
            .andExpect(jsonPath("$.dataBody.programs[0].diseases[0].diseaseKey").value("ND0704"))
            .andExpect(jsonPath("$.dataBody.programs[0].diseases[0].diseaseGroup").value("바이러스"))
            .andExpect(jsonPath("$.dataBody.programs[0].diseases[0].metric.code").value("CASE_COUNT"))
            .andExpect(jsonPath("$.dataBody.programs[0].diseases[0].regionLevel.code").value("NATION"))
            .andExpect(jsonPath("$.dataBody.programs[0].diseases[0].regionCode").value("00"))
            .andExpect(jsonPath("$.dataBody.programs[0].diseases[0].regionName").value("전국"))
            .andExpect(jsonPath("$.dataBody.programs[0].diseases[0].periodStart").value(lastWeek.toString()))
            .andExpect(jsonPath("$.dataBody.programs[0].diseases[0].periodEnd").value(lastWeek.plusDays(6).toString()))
            .andExpect(jsonPath("$.dataBody.programs[0].diseases[0].periodYear").isNumber())
            .andExpect(jsonPath("$.dataBody.programs[0].diseases[0].periodWeek").isNumber())
            .andExpect(jsonPath("$.dataBody.programs[0].diseases[0].syncedAt").value(matchesPattern(UTC_SECONDS)))
            .andExpect(jsonPath("$.dataBody.programs[0].diseases[0].values.length()").value(1))
            .andExpect(jsonPath("$.dataBody.programs[0].diseases[0].values[0].ageGroup.code").value("ALL"))
            .andExpect(jsonPath("$.dataBody.programs[0].diseases[0].values[0].value").value(152.0));
    }

    @Test
    @DisplayName("회원 토큰으로 불러도 같은 200 이다 — 공개 정보라 권한을 보지 않는다")
    void memberTokenIsAlsoOk() throws Exception {
        insertWeekly(OfficialProgram.ENTERIC, "TOTAL", currentWeekStart.minusWeeks(1), new BigDecimal("432"));

        mockMvc.perform(get(PATH).header(HttpHeaders.AUTHORIZATION, JwtTestTokens.bearer(ACCESS_KEY, 7350912846171L, SecurityRole.USER)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dataBody.programs[0].program.code").value("ENTERIC"));
    }

    @Test
    @DisplayName("전수신고는 지금 주에서 2주 전까지만 보인다 — 진행 중인 주와 지난 주 값은 신고 지연으로 낮게 보여 뺀다. 표본감시는 지연을 두지 않는다")
    void notifiableLagsTwoWeeksButSentinelDoesNot() throws Exception {
        LocalDate twoWeeksAgo = currentWeekStart.minusWeeks(2);
        insertWeekly(OfficialProgram.NOTIFIABLE, "수두", currentWeekStart.minusWeeks(3), new BigDecimal("830"));
        insertWeekly(OfficialProgram.NOTIFIABLE, "수두", twoWeeksAgo, new BigDecimal("812"));
        insertWeekly(OfficialProgram.NOTIFIABLE, "수두", currentWeekStart.minusWeeks(1), new BigDecimal("540"));
        insertWeekly(OfficialProgram.NOTIFIABLE, "수두", currentWeekStart, new BigDecimal("120"));
        insertWeekly(OfficialProgram.ARI, "TOTAL", currentWeekStart.minusWeeks(1), new BigDecimal("1520"));

        mockMvc.perform(get(PATH))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dataBody.programs.length()").value(2))
            .andExpect(jsonPath("$.dataBody.programs[0].program.code").value("NOTIFIABLE"))
            .andExpect(jsonPath("$.dataBody.programs[0].source.code").value("KDCA_NOTIFIABLE"))
            .andExpect(jsonPath("$.dataBody.programs[0].sourceUrl").value((Object) null))
            .andExpect(jsonPath("$.dataBody.programs[0].diseases[0].periodStart").value(twoWeeksAgo.toString()))
            .andExpect(jsonPath("$.dataBody.programs[0].diseases[0].values[0].value").value(812.0))
            .andExpect(jsonPath("$.dataBody.programs[1].program.code").value("ARI"))
            .andExpect(jsonPath("$.dataBody.programs[1].diseases[0].periodStart").value(currentWeekStart.minusWeeks(1).toString()));
    }

    @Test
    @DisplayName("인플루엔자는 연령대별 값을 연령대 순서로 싣고, 원천 빈 칸은 null 로 남긴다")
    void influenzaCarriesAgeGroupsAndNullValues() throws Exception {
        LocalDate lastWeek = currentWeekStart.minusWeeks(1);
        insertWeekly(OfficialProgram.INFLUENZA_ILI, "ILI", OfficialMetric.ILI_PER_1000, OfficialAgeGroup.AGE_65_PLUS, lastWeek, null, SYNCED_AT);
        insertWeekly(OfficialProgram.INFLUENZA_ILI, "ILI", OfficialMetric.ILI_PER_1000, OfficialAgeGroup.AGE_0, lastWeek, new BigDecimal("4.2"), SYNCED_AT);

        mockMvc.perform(get(PATH))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dataBody.programs[0].program.code").value("INFLUENZA_ILI"))
            .andExpect(jsonPath("$.dataBody.programs[0].sourceUrl").value("https://dportal.kdca.go.kr/pot/is/st/influ.do"))
            .andExpect(jsonPath("$.dataBody.programs[0].diseases[0].metric.code").value("ILI_PER_1000"))
            .andExpect(jsonPath("$.dataBody.programs[0].diseases[0].diseaseGroup").value((Object) null))
            .andExpect(jsonPath("$.dataBody.programs[0].diseases[0].values.length()").value(2))
            .andExpect(jsonPath("$.dataBody.programs[0].diseases[0].values[0].ageGroup.code").value("AGE_0"))
            .andExpect(jsonPath("$.dataBody.programs[0].diseases[0].values[0].value").value(4.2))
            .andExpect(jsonPath("$.dataBody.programs[0].diseases[0].values[1].ageGroup.code").value("AGE_65_PLUS"))
            .andExpect(jsonPath("$.dataBody.programs[0].diseases[0].values[1].value").value((Object) null));
    }
}
