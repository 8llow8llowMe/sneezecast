package com.sneezecast.domainlayer.report.adapter.in.web.controller;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.sneezecast.domainlayer.report.adapter.in.web.dto.response.WeeklyReportResponse;
import com.sneezecast.domainlayer.report.adapter.in.web.exception.ReportExceptionHandler;
import com.sneezecast.domainlayer.report.application.command.ReportSubmitCommand;
import com.sneezecast.domainlayer.report.application.exception.ReportErrorCode;
import com.sneezecast.domainlayer.report.application.exception.ReportException;
import com.sneezecast.domainlayer.report.application.port.in.ReportWebUseCase;
import com.sneezecast.domainlayer.report.domain.enums.SymptomGroup;
import com.sneezecast.security.common.dto.MemberLoginActive;
import com.sneezecast.security.common.enums.SecurityRole;
import com.sneezecast.security.common.jwt.JwtAuthentication;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 요청 검증 · 오류 봉투 · 필드별 코드 · 응답 모양을 실제 MVC 경로(Bean Validation + advice)로 본다. 유스케이스는 mock 이고, 인증 주체는
 * SecurityContext 에 직접 넣는다 — {@code @PreAuthorize} · 401/403 은 실제 보안 필터를 거치는 {@code ReportApiIntegrationTest} 가 본다.
 */
class ReportWebControllerTest {

    private static final String PATH = "/api/v1/reports/current";
    private static final long MEMBER_ID = 42L;

    private ReportWebUseCase reportWebUseCase;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        reportWebUseCase = mock(ReportWebUseCase.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new ReportWebController(reportWebUseCase))
            .setControllerAdvice(new ReportExceptionHandler())
            .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
            // Spring Boot 기본 ObjectMapper 처럼 시각을 ISO-8601 문자열로 쓴다 (standalone 기본 변환기는 숫자 타임스탬프다).
            .setMessageConverters(new MappingJackson2HttpMessageConverter(
                Jackson2ObjectMapperBuilder.json().featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build()))
            .build();
        MemberLoginActive principal = MemberLoginActive.builder().memberId(MEMBER_ID).role(SecurityRole.USER).scopes(Set.of("report:write")).build();
        SecurityContextHolder.getContext().setAuthentication(JwtAuthentication.authenticated(principal));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("제출 성공은 {isoWeek, districtCode, symptomGroups(metadata), reportedAt, updatedAt} 봉투다 — 보고 ID · 키 · 수정 횟수는 없다")
    void submitReturnsReportEnvelope() throws Exception {
        when(reportWebUseCase.submitCurrent(eq(MEMBER_ID), eq(command("11230510", SymptomGroup.ENTERIC, SymptomGroup.RESPIRATORY))))
            .thenReturn(response(List.of(SymptomGroup.RESPIRATORY, SymptomGroup.ENTERIC)));

        mockMvc.perform(put(PATH).contentType(MediaType.APPLICATION_JSON)
                .content("{\"districtCode\":\"11230510\",\"symptomGroups\":[\"ENTERIC\",\"RESPIRATORY\"]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dataHeader.success").value(true))
            .andExpect(jsonPath("$.dataBody.isoWeek").value("2026-W40"))
            .andExpect(jsonPath("$.dataBody.districtCode").value("11230510"))
            .andExpect(jsonPath("$.dataBody.symptomGroups.length()").value(2))
            .andExpect(jsonPath("$.dataBody.symptomGroups[0].code").value("RESPIRATORY"))
            .andExpect(jsonPath("$.dataBody.symptomGroups[0].name").value("호흡기"))
            .andExpect(jsonPath("$.dataBody.symptomGroups[0].description").value("발열 · 기침 · 인후통"))
            .andExpect(jsonPath("$.dataBody.symptomGroups[1].code").value("ENTERIC"))
            .andExpect(jsonPath("$.dataBody.reportedAt").value("2026-10-01T05:12:00Z"))
            .andExpect(jsonPath("$.dataBody.updatedAt").value("2026-10-01T06:30:15Z"))
            .andExpect(jsonPath("$.dataBody.id").doesNotExist())
            .andExpect(jsonPath("$.dataBody.reporterKey").doesNotExist())
            .andExpect(jsonPath("$.dataBody.revisionCount").doesNotExist());
    }

    @Test
    @DisplayName("빈 symptomGroups 는 '증상 없음' 정상 보고다 — 모르는 필드(isoWeek)는 무시하고 주를 받지 않는다")
    void emptySymptomGroupsIsNoSymptomReport() throws Exception {
        when(reportWebUseCase.submitCurrent(MEMBER_ID, command("11230510"))).thenReturn(response(List.of()));

        mockMvc.perform(put(PATH).contentType(MediaType.APPLICATION_JSON)
                .content("{\"districtCode\":\"11230510\",\"symptomGroups\":[],\"isoWeek\":\"2020-W01\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dataBody.symptomGroups").isArray())
            .andExpect(jsonPath("$.dataBody.symptomGroups.length()").value(0));
        verify(reportWebUseCase).submitCurrent(MEMBER_ID, command("11230510"));
    }

    static Stream<Arguments> invalidBodies() {
        return Stream.of(
            Arguments.of("districtCode 없음", "{\"symptomGroups\":[]}", "REPORT_101", "districtCode"),
            Arguments.of("districtCode 빈 문자열", "{\"districtCode\":\"\",\"symptomGroups\":[]}", "REPORT_101", "districtCode"),
            Arguments.of("행안부 10자리 코드", "{\"districtCode\":\"1123051000\",\"symptomGroups\":[]}", "REPORT_102", "districtCode"),
            Arguments.of("전각 숫자", "{\"districtCode\":\"１１２３０５１０\",\"symptomGroups\":[]}", "REPORT_102", "districtCode"),
            Arguments.of("symptomGroups 없음", "{\"districtCode\":\"11230510\"}", "REPORT_103", "symptomGroups"),
            Arguments.of("symptomGroups null", "{\"districtCode\":\"11230510\",\"symptomGroups\":null}", "REPORT_103", "symptomGroups"),
            Arguments.of("null 원소", "{\"districtCode\":\"11230510\",\"symptomGroups\":[\"RESPIRATORY\",null]}", "REPORT_104", "symptomGroups[1]"),
            Arguments.of("중복 원소", "{\"districtCode\":\"11230510\",\"symptomGroups\":[\"ENTERIC\",\"ENTERIC\"]}", "REPORT_105", "symptomGroups"),
            Arguments.of("알 수 없는 증상군", "{\"districtCode\":\"11230510\",\"symptomGroups\":[\"FEVER\"]}", "REPORT_100", "symptomGroups[0]"),
            Arguments.of("소문자 증상군", "{\"districtCode\":\"11230510\",\"symptomGroups\":[\"respiratory\"]}", "REPORT_100", "symptomGroups[0]"),
            Arguments.of("배열이 아닌 증상군", "{\"districtCode\":\"11230510\",\"symptomGroups\":\"RESPIRATORY\"}", "REPORT_100", "symptomGroups"),
            Arguments.of("깨진 JSON", "{\"districtCode\":", "REPORT_100", "request")
        );
    }

    @ParameterizedTest(name = "{0} → {2}")
    @MethodSource("invalidBodies")
    @DisplayName("잘못된 본문은 필드별 코드 400 봉투이고 유스케이스를 부르지 않는다 — null · 중복 원소를 조용히 접지 않는다")
    void invalidBodyIsRejected(String description, String body, String code, String field) throws Exception {
        mockMvc.perform(put(PATH).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.dataHeader.success").value(false))
            .andExpect(jsonPath("$.dataHeader.resultCode").value(code))
            .andExpect(jsonPath("$.dataHeader.fieldErrors[0].field").value(field))
            .andExpect(jsonPath("$.dataBody").doesNotExist());
        verifyNoInteractions(reportWebUseCase);
    }

    @Test
    @DisplayName("본문이 없으면 REPORT_100 봉투다")
    void missingBodyIsRejected() throws Exception {
        mockMvc.perform(put(PATH).contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("REPORT_100"));
        verifyNoInteractions(reportWebUseCase);
    }

    static Stream<Arguments> businessErrors() {
        return Stream.of(
            Arguments.of(ReportErrorCode.DISTRICT_NOT_FOUND, 400),
            Arguments.of(ReportErrorCode.DISTRICT_RETIRED, 400),
            Arguments.of(ReportErrorCode.CONCURRENT_SUBMISSION, 409)
        );
    }

    @ParameterizedTest(name = "{0} → {1}")
    @MethodSource("businessErrors")
    @DisplayName("도메인 예외는 그 코드 · 상태의 실패 봉투다")
    void businessErrorIsEnveloped(ReportErrorCode errorCode, int httpStatus) throws Exception {
        when(reportWebUseCase.submitCurrent(eq(MEMBER_ID), eq(command("11230510")))).thenThrow(new ReportException(errorCode));

        mockMvc.perform(put(PATH).contentType(MediaType.APPLICATION_JSON).content("{\"districtCode\":\"11230510\",\"symptomGroups\":[]}"))
            .andExpect(status().is(httpStatus))
            .andExpect(jsonPath("$.dataHeader.success").value(false))
            .andExpect(jsonPath("$.dataHeader.resultCode").value(errorCode.getCode()))
            .andExpect(jsonPath("$.dataHeader.resultMessage").value(errorCode.getMessage()))
            .andExpect(jsonPath("$.dataBody").doesNotExist());
    }

    @Test
    @DisplayName("이번 주 보고가 있으면 조회가 같은 모양을 준다")
    void getReturnsCurrentReport() throws Exception {
        when(reportWebUseCase.getCurrent(MEMBER_ID)).thenReturn(response(List.of(SymptomGroup.ENTERIC)));

        mockMvc.perform(get(PATH))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dataBody.isoWeek").value("2026-W40"))
            .andExpect(jsonPath("$.dataBody.symptomGroups[0].code").value("ENTERIC"))
            .andExpect(jsonPath("$.dataBody.reportedAt").value("2026-10-01T05:12:00Z"));
    }

    @Test
    @DisplayName("아직 보고하지 않았으면 200 이고 dataBody 는 null 이다 — 회원 · 주당 0~1개인 하위 리소스의 부재")
    void getWithoutReportIsNullBody() throws Exception {
        when(reportWebUseCase.getCurrent(anyLong())).thenReturn(null);

        mockMvc.perform(get(PATH))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dataHeader.success").value(true))
            .andExpect(jsonPath("$.dataBody").isEmpty());
        verify(reportWebUseCase).getCurrent(MEMBER_ID);
    }

    @Test
    @DisplayName("취소는 200 성공 봉투이고 dataBody 가 없다")
    void cancelReturnsSuccess() throws Exception {
        mockMvc.perform(delete(PATH))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dataHeader.success").value(true))
            .andExpect(jsonPath("$.dataBody").isEmpty());
        verify(reportWebUseCase).cancelCurrent(MEMBER_ID);
    }

    private static ReportSubmitCommand command(String districtCode, SymptomGroup... symptomGroups) {
        return ReportSubmitCommand.of(districtCode, List.of(symptomGroups));
    }

    private static WeeklyReportResponse response(List<SymptomGroup> symptomGroups) {
        return WeeklyReportResponse.builder()
            .isoWeek("2026-W40")
            .districtCode("11230510")
            .symptomGroups(symptomGroups.stream().map(SymptomGroup::toMetadata).toList())
            .reportedAt(Instant.parse("2026-10-01T05:12:00Z"))
            .updatedAt(Instant.parse("2026-10-01T06:30:15Z"))
            .build();
    }
}
