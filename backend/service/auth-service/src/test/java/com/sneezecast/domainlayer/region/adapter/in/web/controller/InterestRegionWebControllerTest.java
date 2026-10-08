package com.sneezecast.domainlayer.region.adapter.in.web.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sneezecast.domainlayer.region.adapter.in.web.dto.response.MemberRegionResponse;
import com.sneezecast.domainlayer.region.adapter.in.web.exception.RegionExceptionHandler;
import com.sneezecast.domainlayer.region.application.exception.RegionErrorCode;
import com.sneezecast.domainlayer.region.application.exception.RegionException;
import com.sneezecast.domainlayer.region.application.port.in.InterestRegionWebUseCase;
import com.sneezecast.persistence.dto.SliceResponse;
import com.sneezecast.security.common.dto.MemberLoginActive;
import com.sneezecast.security.common.enums.SecurityRole;
import com.sneezecast.security.common.jwt.JwtAuthentication;
import java.util.List;
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
 * 관심 동네 요청 검증(본문 · 경로 변수) · 오류 봉투 · 목록 모양을 실제 MVC 경로(Bean Validation + advice)로 본다. 유스케이스는 mock 이다.
 * 토큰이 없을 때 401 인지(메서드 보안)는 {@code InterestRegionApiIntegrationTest} 가 실제 필터 체인으로 본다.
 */
class InterestRegionWebControllerTest {

    private static final String PATH = "/api/v1/members/me/interest-regions";
    private static final long MEMBER_ID = 42L;
    private static final SliceResponse<MemberRegionResponse> TWO_REGIONS = new SliceResponse<>(List.of(
        new MemberRegionResponse("11230510", "역삼1동", "서울특별시 강남구", false),
        new MemberRegionResponse("21120560", null, null, true)), false);

    private InterestRegionWebUseCase useCase;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        useCase = mock(InterestRegionWebUseCase.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new InterestRegionWebController(useCase))
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
    @DisplayName("목록은 200 성공 봉투의 SliceResponse 다 — 고른 순서, hasNext=false, 이름을 모르는 동네는 name null")
    void listSucceeds() throws Exception {
        when(useCase.getMyInterestRegions(MEMBER_ID)).thenReturn(TWO_REGIONS);

        mockMvc.perform(get(PATH))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dataHeader.success").value(true))
            .andExpect(jsonPath("$.dataBody.hasNext").value(false))
            .andExpect(jsonPath("$.dataBody.contents.length()").value(2))
            .andExpect(jsonPath("$.dataBody.contents[0].code").value("11230510"))
            .andExpect(jsonPath("$.dataBody.contents[0].name").value("역삼1동"))
            .andExpect(jsonPath("$.dataBody.contents[0].sigungu").value("서울특별시 강남구"))
            .andExpect(jsonPath("$.dataBody.contents[0].abolished").value(false))
            .andExpect(jsonPath("$.dataBody.contents[1].name").value(nullValue()))
            .andExpect(jsonPath("$.dataBody.contents[1].abolished").value(true));
    }

    @Test
    @DisplayName("하나도 없으면 빈 contents 다 (dataBody null 이 아니다)")
    void emptyList() throws Exception {
        when(useCase.getMyInterestRegions(MEMBER_ID)).thenReturn(new SliceResponse<>(List.of(), false));

        mockMvc.perform(get(PATH))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dataBody.contents.length()").value(0))
            .andExpect(jsonPath("$.dataBody.hasNext").value(false));
    }

    @Test
    @DisplayName("추가는 200 에 더한 뒤의 목록을 싣고, 회원은 JWT 주체로 넘긴다")
    void addSucceeds() throws Exception {
        when(useCase.addMyInterestRegion(MEMBER_ID, "11230510")).thenReturn(TWO_REGIONS);

        postJson("{\"code\":\"11230510\"}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dataBody.contents[0].code").value("11230510"));

        verify(useCase).addMyInterestRegion(MEMBER_ID, "11230510");
    }

    @Test
    @DisplayName("추가에 code 가 없으면 400 REGION_101 이고 유스케이스를 부르지 않는다")
    void addWithoutCode() throws Exception {
        postJson("{}")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("REGION_101"))
            .andExpect(jsonPath("$.dataHeader.fieldErrors[0].field").value("code"))
            .andExpect(jsonPath("$.dataHeader.fieldErrors.length()").value(1));

        verifyNoInteractions(useCase);
    }

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = {"", " ", "1123051", "112305100", "1123051a", "1123051０"})
    @DisplayName("추가 code 가 숫자 8자리가 아니면 400 REGION_102 하나만 낸다")
    void addWithMalformedCode(String code) throws Exception {
        postJson("{\"code\":\"" + code + "\"}")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("REGION_102"))
            .andExpect(jsonPath("$.dataHeader.fieldErrors.length()").value(1));

        verifyNoInteractions(useCase);
    }

    @Test
    @DisplayName("추가 본문이 깨졌으면 봉투에 담긴 400 REGION_100 이다")
    void addWithUnreadableBody() throws Exception {
        postJson("{\"code\":")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("REGION_100"));

        verifyNoInteractions(useCase);
    }

    @ParameterizedTest
    @EnumSource(value = RegionErrorCode.class, names = {
        "INTEREST_REGION_LIMIT_EXCEEDED", "INTEREST_REGION_ALREADY_SELECTED", "INTEREST_REGION_SAME_AS_MY_REGION", "REGION_SAVE_CONFLICT"})
    @DisplayName("추가 거절(상한 · 이미 고름 · 내 동네와 같음 · 동시 추가)은 409 봉투이고 목록을 싣지 않는다 — 화면이 GET 을 다시 부른다")
    void addConflictsAreEnvelopedWithoutList(RegionErrorCode errorCode) throws Exception {
        doThrow(new RegionException(errorCode)).when(useCase).addMyInterestRegion(anyLong(), anyString());

        postJson("{\"code\":\"11230510\"}")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.dataHeader.success").value(false))
            .andExpect(jsonPath("$.dataHeader.resultCode").value(errorCode.getCode()))
            .andExpect(jsonPath("$.dataHeader.resultMessage").value(errorCode.getMessage()))
            .andExpect(jsonPath("$.dataBody").value(nullValue()));
    }

    @Test
    @DisplayName("추가 중 없는 코드 · 폐지는 400, surveillance 장애는 503 봉투다")
    void addLookupErrors() throws Exception {
        assertAddError(RegionErrorCode.DISTRICT_NOT_FOUND, 400);
        assertAddError(RegionErrorCode.DISTRICT_ABOLISHED, 400);
        assertAddError(RegionErrorCode.INTERNAL_SERVICE_UNAVAILABLE, 503);
    }

    @Test
    @DisplayName("삭제는 200 에 뺀 뒤의 목록을 싣는다")
    void removeSucceeds() throws Exception {
        when(useCase.removeMyInterestRegion(MEMBER_ID, "21120561")).thenReturn(TWO_REGIONS);

        mockMvc.perform(delete(PATH + "/21120561"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dataHeader.success").value(true))
            .andExpect(jsonPath("$.dataBody.contents.length()").value(2));

        verify(useCase).removeMyInterestRegion(MEMBER_ID, "21120561");
    }

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = {"1123051", "112305100", "1123051a", "abcdefgh"})
    @DisplayName("삭제 경로의 코드가 숫자 8자리가 아니면 내 동네 요청과 같은 400 REGION_102 봉투이고 유스케이스를 부르지 않는다")
    void removeWithMalformedCode(String code) throws Exception {
        mockMvc.perform(delete(PATH + "/" + code))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.dataHeader.success").value(false))
            .andExpect(jsonPath("$.dataHeader.resultCode").value("REGION_102"))
            .andExpect(jsonPath("$.dataHeader.fieldErrors[0].field").value("code"))
            .andExpect(jsonPath("$.dataHeader.fieldErrors.length()").value(1));

        verifyNoInteractions(useCase);
    }

    @Test
    @DisplayName("목록 · 삭제 중 surveillance 장애는 503 REGION_004 봉투다")
    void listAndRemoveUnavailable() throws Exception {
        when(useCase.getMyInterestRegions(anyLong())).thenThrow(new RegionException(RegionErrorCode.INTERNAL_SERVICE_UNAVAILABLE));
        when(useCase.removeMyInterestRegion(anyLong(), anyString())).thenThrow(new RegionException(RegionErrorCode.INTERNAL_SERVICE_UNAVAILABLE));

        mockMvc.perform(get(PATH))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("REGION_004"));
        mockMvc.perform(delete(PATH + "/11230510"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("REGION_004"));
    }

    private void assertAddError(RegionErrorCode errorCode, int status) throws Exception {
        doThrow(new RegionException(errorCode)).when(useCase).addMyInterestRegion(anyLong(), anyString());

        postJson("{\"code\":\"11230510\"}")
            .andExpect(status().is(status))
            .andExpect(jsonPath("$.dataHeader.resultCode").value(errorCode.getCode()));
    }

    private ResultActions postJson(String body) throws Exception {
        return mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
