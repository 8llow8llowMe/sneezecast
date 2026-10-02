package com.sneezecast.domainlayer.district.adapter.in.internal.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sneezecast.domainlayer.district.adapter.in.internal.dto.response.DistrictInternalResponse;
import com.sneezecast.domainlayer.district.adapter.in.web.exception.DistrictExceptionHandler;
import com.sneezecast.domainlayer.district.application.exception.DistrictErrorCode;
import com.sneezecast.domainlayer.district.application.exception.DistrictException;
import com.sneezecast.domainlayer.district.application.port.in.DistrictInternalUseCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 내부 API 응답이 공개 API 와 같은 {@code Response} 봉투인지 본다 — auth 의 Feign 헬퍼가 이 봉투를 벗긴다. 유스케이스는 mock 이다.
 */
class DistrictInternalControllerTest {

    private DistrictInternalUseCase districtInternalUseCase;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        districtInternalUseCase = mock(DistrictInternalUseCase.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new DistrictInternalController(districtInternalUseCase))
            .setControllerAdvice(new DistrictExceptionHandler())
            .build();
    }

    @Test
    @DisplayName("성공은 200 봉투에 {code, name, sigungu, active} 다")
    void returnsEnvelope() throws Exception {
        when(districtInternalUseCase.getDistrict("11230510")).thenReturn(
            DistrictInternalResponse.builder().code("11230510").name("역삼1동").sigungu("서울특별시 강남구").active(true).build());

        mockMvc.perform(get("/internal/v1/districts/11230510"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dataHeader.success").value(true))
            .andExpect(jsonPath("$.dataBody.code").value("11230510"))
            .andExpect(jsonPath("$.dataBody.name").value("역삼1동"))
            .andExpect(jsonPath("$.dataBody.sigungu").value("서울특별시 강남구"))
            .andExpect(jsonPath("$.dataBody.active").value(true));
    }

    @Test
    @DisplayName("없는 코드는 404 DISTRICT_001 봉투다 — 호출자는 4xx 를 서킷에 세지 않고 resultCode 로 구분한다")
    void missingDistrictIsNotFound() throws Exception {
        when(districtInternalUseCase.getDistrict("99999999")).thenThrow(new DistrictException(DistrictErrorCode.DISTRICT_NOT_FOUND));

        mockMvc.perform(get("/internal/v1/districts/99999999"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.dataHeader.success").value(false))
            .andExpect(jsonPath("$.dataHeader.resultCode").value("DISTRICT_001"));
    }

    @Test
    @DisplayName("코드 형식이 틀리면 400 DISTRICT_103 이고 유스케이스를 부르지 않는다")
    void malformedCodeIsRejected() throws Exception {
        mockMvc.perform(get("/internal/v1/districts/abc"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("DISTRICT_103"));
        verifyNoInteractions(districtInternalUseCase);
    }
}
