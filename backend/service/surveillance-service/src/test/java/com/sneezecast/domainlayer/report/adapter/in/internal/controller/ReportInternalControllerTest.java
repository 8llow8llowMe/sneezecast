package com.sneezecast.domainlayer.report.adapter.in.internal.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sneezecast.domainlayer.report.adapter.in.web.exception.ReportExceptionHandler;
import com.sneezecast.domainlayer.report.application.port.in.ReportInternalUseCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 내부 파기 API 의 응답 모양(본문 없는 204)과 경로 값 검증 · 오류 봉투를 실제 MVC 경로(Bean Validation + advice)로 본다. 유스케이스는 mock 이다
 * — 실제 삭제 · 보안 필터는 {@code ReportInternalApiIntegrationTest} 가 본다.
 */
class ReportInternalControllerTest {

    private static final String PATH = "/internal/v1/reporters/";

    private ReportInternalUseCase reportInternalUseCase;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        reportInternalUseCase = mock(ReportInternalUseCase.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new ReportInternalController(reportInternalUseCase))
            .setControllerAdvice(new ReportExceptionHandler())
            .build();
    }

    @Test
    @DisplayName("성공은 본문 없는 204 이고 경로의 회원 ID 를 그대로 유스케이스에 넘긴다")
    void purgeReturnsNoContent() throws Exception {
        mockMvc.perform(delete(PATH + "7350912846153"))
            .andExpect(status().isNoContent())
            .andExpect(content().string(""));

        verify(reportInternalUseCase).purgeReporter(7350912846153L);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"0", "-1", "-7350912846153"})
    @DisplayName("0 · 음수 회원 ID 는 400 REPORT_106 봉투이고 유스케이스를 부르지 않는다 — 가명 키를 계산하지 않는다")
    void nonPositiveMemberIdIsRejected(String memberId) throws Exception {
        mockMvc.perform(delete(PATH + memberId))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.dataHeader.success").value(false))
            .andExpect(jsonPath("$.dataHeader.resultCode").value("REPORT_106"))
            .andExpect(jsonPath("$.dataHeader.fieldErrors[0].field").value("memberId"))
            .andExpect(jsonPath("$.dataBody").doesNotExist());

        verifyNoInteractions(reportInternalUseCase);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"abc", "1.5", "99999999999999999999"})
    @DisplayName("숫자가 아니거나 long 범위를 넘는 회원 ID 는 400 REPORT_198 봉투이고 유스케이스를 부르지 않는다")
    void malformedMemberIdIsRejected(String memberId) throws Exception {
        mockMvc.perform(delete(PATH + memberId))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.dataHeader.success").value(false))
            .andExpect(jsonPath("$.dataHeader.resultCode").value("REPORT_198"))
            .andExpect(jsonPath("$.dataHeader.fieldErrors[0].field").value("memberId"));

        verifyNoInteractions(reportInternalUseCase);
    }
}
