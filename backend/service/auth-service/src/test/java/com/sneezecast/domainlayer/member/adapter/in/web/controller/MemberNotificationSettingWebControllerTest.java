package com.sneezecast.domainlayer.member.adapter.in.web.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sneezecast.domainlayer.member.adapter.in.web.dto.response.MemberNotificationSettingResponse;
import com.sneezecast.domainlayer.member.adapter.in.web.exception.MemberExceptionHandler;
import com.sneezecast.domainlayer.member.adapter.in.web.exception.MemberRequestExceptionHandler;
import com.sneezecast.domainlayer.member.application.command.MemberNotificationSettingChangeCommand;
import com.sneezecast.domainlayer.member.application.exception.MemberErrorCode;
import com.sneezecast.domainlayer.member.application.exception.MemberException;
import com.sneezecast.domainlayer.member.application.port.in.MemberNotificationSettingWebUseCase;
import com.sneezecast.security.common.dto.MemberLoginActive;
import com.sneezecast.security.common.enums.SecurityRole;
import com.sneezecast.security.common.jwt.JwtAuthentication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 알림 설정 요청 검증(빈 본문 · 모르는 항목 · boolean 이 아닌 값) · 오류 봉투 · 응답 모양을 실제 MVC 경로(Jackson + Bean Validation + advice)로 본다.
 * 유스케이스는 mock 이다. Boot 의 ObjectMapper · 토큰 없음 401 은 {@code MemberNotificationSettingApiIntegrationTest} 가 실제 컨텍스트로 본다.
 */
class MemberNotificationSettingWebControllerTest {

    private static final String PATH = "/api/v1/members/me/notification-settings";
    private static final long MEMBER_ID = 42L;

    private MemberNotificationSettingWebUseCase useCase;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        useCase = mock(MemberNotificationSettingWebUseCase.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new MemberNotificationSettingWebController(useCase))
            .setControllerAdvice(new MemberRequestExceptionHandler(), new MemberExceptionHandler())
            .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
            .build();
        MemberLoginActive principal = MemberLoginActive.builder().memberId(MEMBER_ID).role(SecurityRole.USER).tokenId("access-jti").build();
        SecurityContextHolder.getContext().setAuthentication(JwtAuthentication.authenticated(principal));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("조회는 200 성공 봉투에 설정 전부를 싣고, 회원은 JWT 주체로 넘긴다")
    void getSucceeds() throws Exception {
        when(useCase.getMyNotificationSettings(MEMBER_ID)).thenReturn(new MemberNotificationSettingResponse(true, false));

        mockMvc.perform(get(PATH))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dataHeader.success").value(true))
            .andExpect(jsonPath("$.dataBody.weeklyReport").value(true))
            .andExpect(jsonPath("$.dataBody.regionNotice").value(false))
            .andExpect(jsonPath("$.dataBody.length()").value(2));
    }

    @Test
    @DisplayName("한 항목만 보내면 그 항목만 담은 명령을 넘기고(다른 항목은 null — 그대로 둔다) 바뀐 뒤의 설정 전부를 돌려준다")
    void patchOneItem() throws Exception {
        when(useCase.changeMyNotificationSettings(MEMBER_ID, new MemberNotificationSettingChangeCommand(true, null)))
            .thenReturn(new MemberNotificationSettingResponse(true, true));

        patchJson("{\"weeklyReport\":true}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dataBody.weeklyReport").value(true))
            .andExpect(jsonPath("$.dataBody.regionNotice").value(true));

        verify(useCase).changeMyNotificationSettings(MEMBER_ID, new MemberNotificationSettingChangeCommand(true, null));
    }

    @Test
    @DisplayName("두 항목을 모두 보내면 둘 다 넘긴다 — false 도 값이다")
    void patchBothItems() throws Exception {
        when(useCase.changeMyNotificationSettings(anyLong(), any())).thenReturn(new MemberNotificationSettingResponse(false, true));

        patchJson("{\"weeklyReport\":false,\"regionNotice\":true}").andExpect(status().isOk());

        verify(useCase).changeMyNotificationSettings(MEMBER_ID, new MemberNotificationSettingChangeCommand(false, true));
    }

    @Test
    @DisplayName("빈 본문 {} 은 400 MEMBER_111 하나다 — field 는 본문 전체(request)")
    void emptyBody() throws Exception {
        assertSingleValidationError(patchJson("{}"), "MEMBER_111", "request");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"\"true\"", "\"false\"", "1", "0", "null", "{}", "[]", "\"\""})
    @DisplayName("weeklyReport 가 JSON true / false 가 아니면(문자열 · 숫자 · null · 객체 · 배열) 400 MEMBER_112 하나다")
    void weeklyReportNotBoolean(String value) throws Exception {
        assertSingleValidationError(patchJson("{\"weeklyReport\":" + value + "}"), "MEMBER_112", "weeklyReport");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"\"true\"", "1", "null"})
    @DisplayName("regionNotice 가 JSON true / false 가 아니면 400 MEMBER_113 하나다 — 다른 항목이 올바라도 아무것도 바꾸지 않는다")
    void regionNoticeNotBoolean(String value) throws Exception {
        assertSingleValidationError(patchJson("{\"weeklyReport\":true,\"regionNotice\":" + value + "}"), "MEMBER_113", "regionNotice");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
        "{\"weeklyReport\":true,\"pushEnabled\":true}",
        "{\"weeklyReports\":true}",
        "{\"foo\":null}",
        // DTO 의 내부 이름(검증용 메서드 · 모르는 항목 모음)도 모르는 항목이다 — 값을 받아들이지 않는다.
        "{\"request\":true}",
        "{\"unknownItems\":{\"weeklyReport\":true}}",
        "{\"command\":true}"
    })
    @DisplayName("모르는 항목이 있으면 400 MEMBER_114 하나다 — 아는 항목만 바꾸고 넘어가지 않고, 모르는 항목만 있어도 빈 본문(111)으로 보지 않는다")
    void unknownItem(String body) throws Exception {
        assertSingleValidationError(patchJson(body), "MEMBER_114", "unknownItems");
    }

    @Test
    @DisplayName("오류가 여럿이면 모두 담고 순서는 weeklyReport → regionNotice → unknownItems 로 고정이다")
    void multipleErrorsInFixedOrder() throws Exception {
        patchJson("{\"foo\":1,\"regionNotice\":\"true\",\"weeklyReport\":0}")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("MEMBER_112"))
            .andExpect(jsonPath("$.dataHeader.fieldErrors.length()").value(3))
            .andExpect(jsonPath("$.dataHeader.fieldErrors[0].code").value("MEMBER_112"))
            .andExpect(jsonPath("$.dataHeader.fieldErrors[1].code").value("MEMBER_113"))
            .andExpect(jsonPath("$.dataHeader.fieldErrors[2].code").value("MEMBER_114"));

        verifyNoInteractions(useCase);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"{\"weeklyReport\":", "[]", "\"weeklyReport\"", "true", "null", ""})
    @DisplayName("깨진 JSON · 객체가 아닌 본문 · 본문 없음은 봉투에 담긴 400 MEMBER_100 이다")
    void unreadableBody(String body) throws Exception {
        patchJson(body)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.dataHeader.success").value(false))
            .andExpect(jsonPath("$.dataHeader.resultCode").value("MEMBER_100"));

        verifyNoInteractions(useCase);
    }

    @ParameterizedTest
    @EnumSource(value = MemberErrorCode.class, names = {"MEMBER_NOT_FOUND", "WITHDRAWN_MEMBER", "SUSPENDED_MEMBER", "NOTIFICATION_SETTING_SAVE_CONFLICT"})
    @DisplayName("회원 상태 · 동시 저장 경합은 MEMBER 봉투이고 설정을 싣지 않는다")
    void memberErrorsAreEnveloped(MemberErrorCode errorCode) throws Exception {
        when(useCase.getMyNotificationSettings(anyLong())).thenThrow(new MemberException(errorCode));
        when(useCase.changeMyNotificationSettings(anyLong(), any())).thenThrow(new MemberException(errorCode));

        mockMvc.perform(get(PATH))
            .andExpect(status().is(errorCode.getHttpStatus().value()))
            .andExpect(jsonPath("$.dataHeader.resultCode").value(errorCode.getCode()))
            .andExpect(jsonPath("$.dataBody").value(nullValue()));
        patchJson("{\"regionNotice\":true}")
            .andExpect(status().is(errorCode.getHttpStatus().value()))
            .andExpect(jsonPath("$.dataHeader.resultCode").value(errorCode.getCode()))
            .andExpect(jsonPath("$.dataHeader.resultMessage").value(errorCode.getMessage()))
            .andExpect(jsonPath("$.dataBody").value(nullValue()));
    }

    private void assertSingleValidationError(ResultActions result, String code, String field) throws Exception {
        result.andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.dataHeader.success").value(false))
            .andExpect(jsonPath("$.dataHeader.resultCode").value(code))
            .andExpect(jsonPath("$.dataHeader.fieldErrors.length()").value(1))
            .andExpect(jsonPath("$.dataHeader.fieldErrors[0].code").value(code))
            .andExpect(jsonPath("$.dataHeader.fieldErrors[0].field").value(field))
            .andExpect(jsonPath("$.dataBody").value(nullValue()));

        verifyNoInteractions(useCase);
    }

    private ResultActions patchJson(String body) throws Exception {
        return mockMvc.perform(patch(PATH).contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
