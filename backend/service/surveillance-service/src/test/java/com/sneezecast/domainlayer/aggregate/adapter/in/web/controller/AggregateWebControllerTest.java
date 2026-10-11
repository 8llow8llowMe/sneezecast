package com.sneezecast.domainlayer.aggregate.adapter.in.web.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sneezecast.domainlayer.aggregate.adapter.in.web.dto.item.DistrictWeeklyAggregateItem;
import com.sneezecast.domainlayer.aggregate.adapter.in.web.dto.item.SymptomGroupAggregateItem;
import com.sneezecast.domainlayer.aggregate.adapter.in.web.dto.response.DistrictWeeklyAggregateListResponse;
import com.sneezecast.domainlayer.aggregate.adapter.in.web.dto.response.DistrictWeeklyAggregateResponse;
import com.sneezecast.domainlayer.aggregate.adapter.in.web.exception.AggregateExceptionHandler;
import com.sneezecast.domainlayer.aggregate.application.exception.AggregateErrorCode;
import com.sneezecast.domainlayer.aggregate.application.exception.AggregateException;
import com.sneezecast.domainlayer.aggregate.application.port.in.AggregateWebUseCase;
import com.sneezecast.domainlayer.aggregate.domain.enums.AggregateLevel;
import com.sneezecast.domainlayer.aggregate.domain.enums.GroupTrend;
import com.sneezecast.domainlayer.aggregate.domain.enums.InsufficientReason;
import com.sneezecast.domainlayer.report.domain.enums.SymptomGroup;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 요청 검증 · 오류 봉투 · 필드별 코드 · null 항목 생략을 실제 MVC 경로(Bean Validation + advice + Jackson)로 본다. 유스케이스는 mock 이다.
 */
class AggregateWebControllerTest {

    private AggregateWebUseCase aggregateWebUseCase;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        aggregateWebUseCase = mock(AggregateWebUseCase.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new AggregateWebController(aggregateWebUseCase))
            .setControllerAdvice(new AggregateExceptionHandler())
            .build();
    }

    @Test
    @DisplayName("자료 부족 동네는 수치 항목(symptomaticCount · symptomRate · baselineRate · groups)과 계산 시각이 응답에 아예 없다")
    void insufficientOmitsNumbers() throws Exception {
        when(aggregateWebUseCase.getDistrictWeeklyAggregate("11440660", null)).thenReturn(single(insufficientItem()));

        mockMvc.perform(get("/api/v1/districts/11440660/weekly-aggregate"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dataHeader.success").value(true))
            .andExpect(jsonPath("$.dataBody.isoWeek").value("2026-W42"))
            .andExpect(jsonPath("$.dataBody.finalized").value(false))
            .andExpect(jsonPath("$.dataBody.publicThreshold").value(100))
            .andExpect(jsonPath("$.dataBody.district.level.code").value("INSUFFICIENT"))
            .andExpect(jsonPath("$.dataBody.district.level.name").value("자료 부족"))
            .andExpect(jsonPath("$.dataBody.district.insufficientReason.code").value("LOW_SAMPLE"))
            .andExpect(jsonPath("$.dataBody.district.participantCount").value(0))
            .andExpect(jsonPath("$.dataBody.district.symptomaticCount").doesNotExist())
            .andExpect(jsonPath("$.dataBody.district.symptomRate").doesNotExist())
            .andExpect(jsonPath("$.dataBody.district.baselineRate").doesNotExist())
            .andExpect(jsonPath("$.dataBody.district.groups").doesNotExist())
            .andExpect(jsonPath("$.dataBody.district.calculatedAt").doesNotExist());
    }

    @Test
    @DisplayName("수치가 있는 동네는 비율을 소수로, 단계 · 증상군 · 변화를 {code, name, description} 으로 준다 — 변화가 없으면 trend 를 뺀다")
    void measuredCarriesNumbers() throws Exception {
        DistrictWeeklyAggregateItem item = DistrictWeeklyAggregateItem.builder()
            .code("11440660").name("서교동").sigungu("서울특별시 마포구").active(true)
            .level(AggregateLevel.SLIGHT.toMetadata())
            .participantCount(120)
            .symptomaticCount(20)
            .symptomRate(new BigDecimal("16.7"))
            .baselineRate(new BigDecimal("10.0"))
            .groups(List.of(
                SymptomGroupAggregateItem.builder().group(SymptomGroup.RESPIRATORY.toMetadata()).rate(new BigDecimal("12.5"))
                    .trend(GroupTrend.SLIGHT.toMetadata()).build(),
                SymptomGroupAggregateItem.builder().group(SymptomGroup.ENTERIC.toMetadata()).rate(new BigDecimal("7.5")).build()))
            .build();
        when(aggregateWebUseCase.getDistrictWeeklyAggregate("11440660", "2026-W41")).thenReturn(single(item));

        mockMvc.perform(get("/api/v1/districts/11440660/weekly-aggregate").param("week", "2026-W41"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dataBody.district.insufficientReason").doesNotExist())
            .andExpect(jsonPath("$.dataBody.district.symptomRate").value(16.7))
            .andExpect(jsonPath("$.dataBody.district.baselineRate").value(10.0))
            .andExpect(jsonPath("$.dataBody.district.groups[0].group.code").value("RESPIRATORY"))
            .andExpect(jsonPath("$.dataBody.district.groups[0].rate").value(12.5))
            .andExpect(jsonPath("$.dataBody.district.groups[0].trend.code").value("SLIGHT"))
            .andExpect(jsonPath("$.dataBody.district.groups[1].group.code").value("ENTERIC"))
            .andExpect(jsonPath("$.dataBody.district.groups[1].trend").doesNotExist());
    }

    @ParameterizedTest
    @ValueSource(strings = {"1144066", "114406600", "1144066a", "１１４４０６６０"})
    @DisplayName("코드가 숫자 8자리가 아니면 AGGREGATE_101 이고 유스케이스를 부르지 않는다")
    void malformedCodeIsRejected(String code) throws Exception {
        mockMvc.perform(get("/api/v1/districts/{code}/weekly-aggregate", code))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("AGGREGATE_101"))
            .andExpect(jsonPath("$.dataHeader.fieldErrors[0].field").value("code"));
        verifyNoInteractions(aggregateWebUseCase);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "2026-41", "2026W41", "2026-W1", "2026-W00", "2026-W54", "26-W41", "2026-w41"})
    @DisplayName("주가 YYYY-Www(01~53) 형식이 아니면 AGGREGATE_102 다 — 동네 하나 · 지도 모두")
    void malformedWeekIsRejected(String week) throws Exception {
        mockMvc.perform(get("/api/v1/districts/11440660/weekly-aggregate").param("week", week))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("AGGREGATE_102"))
            .andExpect(jsonPath("$.dataHeader.fieldErrors[0].field").value("week"));
        mockMvc.perform(get("/api/v1/districts/weekly-aggregates").param("sigunguCode", "11440").param("week", week))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("AGGREGATE_102"))
            .andExpect(jsonPath("$.dataHeader.fieldErrors[0].field").value("week"));
        verifyNoInteractions(aggregateWebUseCase);
    }

    @Test
    @DisplayName("없는 코드는 404 AGGREGATE_001, 조회할 수 없는 주는 400 AGGREGATE_002 봉투다")
    void businessErrorsAreEnveloped() throws Exception {
        when(aggregateWebUseCase.getDistrictWeeklyAggregate("99999999", null)).thenThrow(new AggregateException(AggregateErrorCode.DISTRICT_NOT_FOUND));
        when(aggregateWebUseCase.getDistrictWeeklyAggregate("11440660", "2027-W01"))
            .thenThrow(new AggregateException(AggregateErrorCode.WEEK_NOT_QUERYABLE, "2025-W43", "2026-W42"));

        mockMvc.perform(get("/api/v1/districts/99999999/weekly-aggregate"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("AGGREGATE_001"))
            .andExpect(jsonPath("$.dataBody").doesNotExist());
        mockMvc.perform(get("/api/v1/districts/11440660/weekly-aggregate").param("week", "2027-W01"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("AGGREGATE_002"))
            .andExpect(jsonPath("$.dataHeader.resultMessage").value("조회할 수 없는 주입니다. (조회할 수 있는 주: 2025-W43 ~ 2026-W42)"));
    }

    @Test
    @DisplayName("지도 — 시군구 코드 · 찾은 동네 · 주를 그대로 넘기고 districts 배열을 준다")
    void listPassesParameters() throws Exception {
        when(aggregateWebUseCase.getDistrictWeeklyAggregates("11440", "11230510", null)).thenReturn(DistrictWeeklyAggregateListResponse.builder()
            .isoWeek("2026-W42").publicThreshold(100).slightDeltaPp(3).highDeltaPp(8).districts(List.of(insufficientItem())).build());

        mockMvc.perform(get("/api/v1/districts/weekly-aggregates").param("sigunguCode", "11440").param("include", "11230510"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dataBody.districts.length()").value(1))
            .andExpect(jsonPath("$.dataBody.districts[0].code").value("11440660"))
            .andExpect(jsonPath("$.dataBody.districts[0].symptomRate").doesNotExist());
        verify(aggregateWebUseCase).getDistrictWeeklyAggregates("11440", "11230510", null);
    }

    @Test
    @DisplayName("지도 — 시군구 코드가 없으면 AGGREGATE_103 이다")
    void missingSigunguIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/districts/weekly-aggregates"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("AGGREGATE_103"))
            .andExpect(jsonPath("$.dataHeader.fieldErrors[0].field").value("sigunguCode"));
        verifyNoInteractions(aggregateWebUseCase);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "1144", "114400", "1144a"})
    @DisplayName("지도 — 시군구 코드가 숫자 5자리가 아니면 AGGREGATE_104 이다 (빈 값은 필수가 아니라 형식 오류 하나)")
    void malformedSigunguIsRejected(String sigunguCode) throws Exception {
        mockMvc.perform(get("/api/v1/districts/weekly-aggregates").param("sigunguCode", sigunguCode))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("AGGREGATE_104"))
            .andExpect(jsonPath("$.dataHeader.fieldErrors.length()").value(1));
        verifyNoInteractions(aggregateWebUseCase);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "1144066", "1123051000"})
    @DisplayName("지도 — 찾은 동네 코드가 숫자 8자리가 아니면 AGGREGATE_101 이다")
    void malformedIncludeIsRejected(String include) throws Exception {
        mockMvc.perform(get("/api/v1/districts/weekly-aggregates").param("sigunguCode", "11440").param("include", include))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("AGGREGATE_101"))
            .andExpect(jsonPath("$.dataHeader.fieldErrors[0].field").value("include"));
        verifyNoInteractions(aggregateWebUseCase);
    }

    @Test
    @DisplayName("지도 — 없는 찾은 동네는 404 AGGREGATE_001 이다")
    void missingIncludeIsNotFound() throws Exception {
        when(aggregateWebUseCase.getDistrictWeeklyAggregates(any(), any(), isNull())).thenThrow(new AggregateException(AggregateErrorCode.DISTRICT_NOT_FOUND));

        mockMvc.perform(get("/api/v1/districts/weekly-aggregates").param("sigunguCode", "11440").param("include", "99999999"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("AGGREGATE_001"));
    }

    private static DistrictWeeklyAggregateResponse single(DistrictWeeklyAggregateItem item) {
        return DistrictWeeklyAggregateResponse.builder().isoWeek("2026-W42").finalized(false).publicThreshold(100).slightDeltaPp(3).highDeltaPp(8)
            .district(item).build();
    }

    private static DistrictWeeklyAggregateItem insufficientItem() {
        return DistrictWeeklyAggregateItem.builder()
            .code("11440660").name("서교동").sigungu("서울특별시 마포구").active(true)
            .level(AggregateLevel.INSUFFICIENT.toMetadata())
            .insufficientReason(InsufficientReason.LOW_SAMPLE.toMetadata())
            .participantCount(0)
            .build();
    }
}
