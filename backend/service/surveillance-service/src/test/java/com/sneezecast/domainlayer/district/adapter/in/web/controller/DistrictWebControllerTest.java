package com.sneezecast.domainlayer.district.adapter.in.web.controller;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sneezecast.domainlayer.district.adapter.in.web.dto.item.DistrictSearchItem;
import com.sneezecast.domainlayer.district.adapter.in.web.dto.response.DistrictDetailResponse;
import com.sneezecast.domainlayer.district.adapter.in.web.exception.DistrictExceptionHandler;
import com.sneezecast.domainlayer.district.application.exception.DistrictErrorCode;
import com.sneezecast.domainlayer.district.application.exception.DistrictException;
import com.sneezecast.domainlayer.district.application.port.in.DistrictWebUseCase;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 요청 검증 · 오류 봉투 · 필드별 코드를 실제 MVC 경로(Bean Validation + advice)로 본다. 유스케이스는 mock 이다.
 */
class DistrictWebControllerTest {

    private DistrictWebUseCase districtWebUseCase;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        districtWebUseCase = mock(DistrictWebUseCase.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new DistrictWebController(districtWebUseCase))
            .setControllerAdvice(new DistrictExceptionHandler())
            .build();
    }

    @Test
    @DisplayName("검색 성공은 200 성공 봉투이고 dataBody 는 배열이다 — 검색어는 앞뒤 공백을 걷어 넘긴다")
    void searchReturnsArrayEnvelope() throws Exception {
        when(districtWebUseCase.searchDistricts("역삼")).thenReturn(List.of(
            DistrictSearchItem.builder().code("11230510").name("역삼1동").sigungu("서울특별시 강남구").build()));

        mockMvc.perform(get("/api/v1/districts").param("query", " 역삼 "))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dataHeader.success").value(true))
            .andExpect(jsonPath("$.dataBody").isArray())
            .andExpect(jsonPath("$.dataBody[0].code").value("11230510"))
            .andExpect(jsonPath("$.dataBody[0].name").value("역삼1동"))
            .andExpect(jsonPath("$.dataBody[0].sigungu").value("서울특별시 강남구"));
        verify(districtWebUseCase).searchDistricts("역삼");
    }

    @Test
    @DisplayName("결과가 없으면 null 이 아니라 빈 배열이다")
    void emptySearchIsEmptyArray() throws Exception {
        when(districtWebUseCase.searchDistricts("없는동네")).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/districts").param("query", "없는동네"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dataBody").isArray())
            .andExpect(jsonPath("$.dataBody.length()").value(0));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "　"})
    @DisplayName("비었거나 공백뿐인 검색어는 DISTRICT_101 필드 오류이고, 유스케이스를 부르지 않는다")
    void blankQueryIsFieldError(String query) throws Exception {
        mockMvc.perform(get("/api/v1/districts").param("query", query))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.dataHeader.success").value(false))
            .andExpect(jsonPath("$.dataHeader.resultCode").value("DISTRICT_101"))
            .andExpect(jsonPath("$.dataHeader.fieldErrors[0].field").value("query"))
            .andExpect(jsonPath("$.dataHeader.fieldErrors.length()").value(1));
        verifyNoInteractions(districtWebUseCase);
    }

    @Test
    @DisplayName("query 파라미터가 없으면 DISTRICT_101 이다")
    void missingQueryIsFieldError() throws Exception {
        mockMvc.perform(get("/api/v1/districts"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("DISTRICT_101"));
        verifyNoInteractions(districtWebUseCase);
    }

    @Test
    @DisplayName("공백을 뺀 21자는 DISTRICT_102 이고, 20자는 앞뒤 공백이 붙어 있어도 받는다")
    void queryLengthIsCheckedAfterStrip() throws Exception {
        mockMvc.perform(get("/api/v1/districts").param("query", "가".repeat(21)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("DISTRICT_102"))
            .andExpect(jsonPath("$.dataHeader.fieldErrors[0].field").value("query"));
        verifyNoInteractions(districtWebUseCase);

        when(districtWebUseCase.searchDistricts(anyString())).thenReturn(List.of());
        mockMvc.perform(get("/api/v1/districts").param("query", "  " + "가".repeat(20) + "  "))
            .andExpect(status().isOk());
        verify(districtWebUseCase).searchDistricts("가".repeat(20));
    }

    @Test
    @DisplayName("단건 조회 성공은 {code, name, sigungu, active} 봉투다")
    void getDistrictReturnsEnvelope() throws Exception {
        when(districtWebUseCase.getDistrict("21120560")).thenReturn(
            DistrictDetailResponse.builder().code("21120560").name("녹산동").sigungu("부산광역시 강서구").active(false).build());

        mockMvc.perform(get("/api/v1/districts/21120560"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dataHeader.success").value(true))
            .andExpect(jsonPath("$.dataBody.code").value("21120560"))
            .andExpect(jsonPath("$.dataBody.name").value("녹산동"))
            .andExpect(jsonPath("$.dataBody.sigungu").value("부산광역시 강서구"))
            .andExpect(jsonPath("$.dataBody.active").value(false));
    }

    @Test
    @DisplayName("없는 코드는 404 DISTRICT_001 봉투다")
    void missingDistrictIsNotFound() throws Exception {
        when(districtWebUseCase.getDistrict("99999999")).thenThrow(new DistrictException(DistrictErrorCode.DISTRICT_NOT_FOUND));

        mockMvc.perform(get("/api/v1/districts/99999999"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.dataHeader.success").value(false))
            .andExpect(jsonPath("$.dataHeader.resultCode").value("DISTRICT_001"))
            .andExpect(jsonPath("$.dataBody").doesNotExist());
    }

    @ParameterizedTest
    @ValueSource(strings = {"1123051", "112305100", "1123051000", "1123051a", "１１２３０５１０"})
    @DisplayName("코드가 숫자 8자리가 아니면 DISTRICT_103 이고 유스케이스를 부르지 않는다 — 전각 숫자 · 행안부 10자리도 막는다")
    void malformedCodeIsRejected(String code) throws Exception {
        mockMvc.perform(get("/api/v1/districts/{code}", code))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("DISTRICT_103"))
            .andExpect(jsonPath("$.dataHeader.fieldErrors[0].field").value("code"));
        verifyNoInteractions(districtWebUseCase);
    }
}
