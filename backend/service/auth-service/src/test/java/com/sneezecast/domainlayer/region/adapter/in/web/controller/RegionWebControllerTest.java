package com.sneezecast.domainlayer.region.adapter.in.web.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sneezecast.domainlayer.region.adapter.in.web.dto.response.MemberRegionResponse;
import com.sneezecast.domainlayer.region.adapter.in.web.exception.RegionExceptionHandler;
import com.sneezecast.domainlayer.region.application.exception.RegionErrorCode;
import com.sneezecast.domainlayer.region.application.exception.RegionException;
import com.sneezecast.domainlayer.region.application.port.in.RegionWebUseCase;
import com.sneezecast.security.common.dto.MemberLoginActive;
import com.sneezecast.security.common.enums.SecurityRole;
import com.sneezecast.security.common.jwt.JwtAuthentication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 요청 검증 · 오류 봉투 · 필드별 코드를 실제 MVC 경로(Bean Validation + advice)로 본다. 유스케이스는 mock 이다.
 * 인증 주체는 SecurityContext 에 직접 세운다 — 토큰이 없을 때 401 인지(메서드 보안)는 {@code RegionApiIntegrationTest} 가 실제 필터 체인으로 본다.
 */
class RegionWebControllerTest {

    private static final String PATH = "/api/v1/members/me/region";
    private static final long MEMBER_ID = 42L;

    private RegionWebUseCase regionWebUseCase;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        regionWebUseCase = mock(RegionWebUseCase.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new RegionWebController(regionWebUseCase))
            .setControllerAdvice(new RegionExceptionHandler())
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
    @DisplayName("저장 성공은 200 성공 봉투에 저장된 동네를 싣고, 회원은 JWT 주체로 넘긴다")
    void saveSucceeds() throws Exception {
        when(regionWebUseCase.saveMyRegion(MEMBER_ID, "11230510"))
            .thenReturn(new MemberRegionResponse("11230510", "역삼1동", "서울특별시 강남구", false));

        putJson("{\"code\":\"11230510\"}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dataHeader.success").value(true))
            .andExpect(jsonPath("$.dataBody.code").value("11230510"))
            .andExpect(jsonPath("$.dataBody.name").value("역삼1동"))
            .andExpect(jsonPath("$.dataBody.sigungu").value("서울특별시 강남구"))
            .andExpect(jsonPath("$.dataBody.abolished").value(false));

        verify(regionWebUseCase).saveMyRegion(MEMBER_ID, "11230510");
    }

    @Test
    @DisplayName("code 가 없으면 400 REGION_101 이고 surveillance 까지 가지 않는다")
    void missingCodeIsRequired() throws Exception {
        putJson("{}")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.dataHeader.success").value(false))
            .andExpect(jsonPath("$.dataHeader.resultCode").value("REGION_101"))
            .andExpect(jsonPath("$.dataHeader.fieldErrors[0].field").value("code"))
            .andExpect(jsonPath("$.dataHeader.fieldErrors.length()").value(1));

        verifyNoInteractions(regionWebUseCase);
    }

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = {"", " ", "1123051", "112305100", "1123051a", "1123051０", "1124066000"})
    @DisplayName("숫자 8자리가 아니면 400 REGION_102 하나만 낸다 — 빈 문자열에 필수 오류가 겹치지 않는다")
    void malformedCodeIsFormatError(String code) throws Exception {
        putJson("{\"code\":\"" + code + "\"}")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("REGION_102"))
            .andExpect(jsonPath("$.dataHeader.fieldErrors.length()").value(1));

        verifyNoInteractions(regionWebUseCase);
    }

    @Test
    @DisplayName("본문이 깨졌으면 봉투에 담긴 400 REGION_100 이다")
    void unreadableBodyIsInvalidRequest() throws Exception {
        putJson("{\"code\":")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("REGION_100"));

        verifyNoInteractions(regionWebUseCase);
    }

    @Test
    @DisplayName("폐지 · 없는 코드는 400, 동시 저장 경합은 409, surveillance 장애는 503 — 각 코드가 봉투에 실린다")
    void domainErrorsAreEnveloped() throws Exception {
        assertDomainError("21120560", RegionErrorCode.DISTRICT_ABOLISHED, 400);
        assertDomainError("99999999", RegionErrorCode.DISTRICT_NOT_FOUND, 400);
        assertDomainError("11230510", RegionErrorCode.REGION_SAVE_CONFLICT, 409);
        assertDomainError("11240660", RegionErrorCode.INTERNAL_SERVICE_UNAVAILABLE, 503);
    }

    @Test
    @DisplayName("아직 고르지 않았으면 200 에 dataBody 가 null 이다 (404 가 아니다)")
    void getWithoutRegionIsNullBody() throws Exception {
        when(regionWebUseCase.getMyRegion(MEMBER_ID)).thenReturn(null);

        mockMvc.perform(get(PATH))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dataHeader.success").value(true))
            .andExpect(jsonPath("$.dataBody").value(nullValue()));
    }

    @Test
    @DisplayName("폐지된 동네도 200 으로 돌려주고 abolished=true 로 다시 고르게 한다")
    void getAbolishedRegion() throws Exception {
        when(regionWebUseCase.getMyRegion(MEMBER_ID)).thenReturn(new MemberRegionResponse("21120560", null, null, true));

        mockMvc.perform(get(PATH))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dataBody.code").value("21120560"))
            .andExpect(jsonPath("$.dataBody.name").value(nullValue()))
            .andExpect(jsonPath("$.dataBody.abolished").value(true));
    }

    @Test
    @DisplayName("조회 중 surveillance 장애는 503 REGION_004 봉투다")
    void getUnavailable() throws Exception {
        when(regionWebUseCase.getMyRegion(anyLong())).thenThrow(new RegionException(RegionErrorCode.INTERNAL_SERVICE_UNAVAILABLE));

        mockMvc.perform(get(PATH))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("REGION_004"))
            .andExpect(jsonPath("$.dataBody").value(nullValue()));
    }

    private void assertDomainError(String code, RegionErrorCode errorCode, int status) throws Exception {
        doThrow(new RegionException(errorCode)).when(regionWebUseCase).saveMyRegion(anyLong(), anyString());

        putJson("{\"code\":\"" + code + "\"}")
            .andExpect(status().is(status))
            .andExpect(jsonPath("$.dataHeader.success").value(false))
            .andExpect(jsonPath("$.dataHeader.resultCode").value(errorCode.getCode()))
            .andExpect(jsonPath("$.dataHeader.resultMessage").value(errorCode.getMessage()));
    }

    private ResultActions putJson(String body) throws Exception {
        return mockMvc.perform(put(PATH).contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
