package com.sneezecast.domainlayer.district;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 공개 · 내부 행정동 API 를 보안 필터 체인부터 H2 까지 실제 빈으로 부른다. 토큰을 싣지 않는다 — 비로그인 둘러보기와 서비스 간 호출이
 * 인증 없이 통과해야 한다. {@code sigungu} 조립(세종형 포함)과 Presenter 까지의 응답 모양을 여기서 본다.
 */
class DistrictApiIntegrationTest extends DistrictH2TestSupport {

    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        clearDistricts();
        insertActive("11230510", "역삼1동", "서울특별시", "강남구");
        insertActive("11230520", "역삼2동", "서울특별시", "강남구");
        insert("21120560", "녹산동", "부산광역시", "강서구", RETIRED_IN_2024);
        insertActive("21120561", "녹산동", "부산광역시", "강서구");
        // 시군구가 없는 시도 — SGIS 가 시군구 이름을 시도 이름과 같게 주는 경우와 비우는 경우를 모두 둔다.
        insertActive("29010110", "한솔동", "세종특별자치시", "세종특별자치시");
        insertActive("29010120", "도담동", "세종특별자치시", "");
    }

    @Test
    @DisplayName("토큰 없이 검색하면 200 이고 dataBody 는 {code, name, sigungu} 배열이다 — active 는 싣지 않는다")
    void anonymousSearchReturnsArray() throws Exception {
        mockMvc.perform(get("/api/v1/districts").param("query", "  역삼  "))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dataHeader.success").value(true))
            .andExpect(jsonPath("$.dataBody.length()").value(2))
            .andExpect(jsonPath("$.dataBody[0].code").value("11230510"))
            .andExpect(jsonPath("$.dataBody[0].name").value("역삼1동"))
            .andExpect(jsonPath("$.dataBody[0].sigungu").value("서울특별시 강남구"))
            .andExpect(jsonPath("$.dataBody[0].active").doesNotExist())
            .andExpect(jsonPath("$.dataBody[1].code").value("11230520"));
    }

    @Test
    @DisplayName("세종처럼 시군구가 없으면 sigungu 는 시도 이름만이다 — 시군구가 시도와 같거나 비어 있어도 같다")
    void sejongStyleSigunguIsSidoOnly() throws Exception {
        mockMvc.perform(get("/api/v1/districts").param("query", "세종"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dataBody.length()").value(2))
            .andExpect(jsonPath("$.dataBody[0].sigungu").value("세종특별자치시"))
            .andExpect(jsonPath("$.dataBody[1].sigungu").value("세종특별자치시"));
    }

    @Test
    @DisplayName("검색은 20건에서 자른다 (프론트 계약)")
    void searchIsCappedAtTwenty() throws Exception {
        IntStream.rangeClosed(1, 25)
            .forEach(index -> insertActive("980000%02d".formatted(index), "테스트%d동".formatted(index), "테스트도", "테스트군"));

        mockMvc.perform(get("/api/v1/districts").param("query", "테스트"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dataBody.length()").value(20))
            .andExpect(jsonPath("$.dataBody[19].code").value("98000020"));
    }

    @Test
    @DisplayName("검색어 '%' 는 전체 목록이 아니라 빈 배열이다")
    void percentQueryIsNotWildcard() throws Exception {
        mockMvc.perform(get("/api/v1/districts").param("query", "%"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dataBody.length()").value(0));
    }

    @Test
    @DisplayName("공백뿐인 검색어는 실제 경로에서도 DISTRICT_101 봉투다")
    void blankQueryIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/districts").param("query", "   "))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.dataHeader.success").value(false))
            .andExpect(jsonPath("$.dataHeader.resultCode").value("DISTRICT_101"));
    }

    @Test
    @DisplayName("단건 조회 — 현행은 active=true, 세종형은 sigungu 가 시도 이름만이다")
    void getActiveDistrict() throws Exception {
        mockMvc.perform(get("/api/v1/districts/11230510"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dataBody.code").value("11230510"))
            .andExpect(jsonPath("$.dataBody.name").value("역삼1동"))
            .andExpect(jsonPath("$.dataBody.sigungu").value("서울특별시 강남구"))
            .andExpect(jsonPath("$.dataBody.active").value(true));
        mockMvc.perform(get("/api/v1/districts/29010120"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dataBody.sigungu").value("세종특별자치시"));
    }

    @Test
    @DisplayName("폐지된 코드도 200 이고 active=false 다 — 화면이 재선택을 안내한다")
    void getRetiredDistrict() throws Exception {
        mockMvc.perform(get("/api/v1/districts/21120560"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dataBody.code").value("21120560"))
            .andExpect(jsonPath("$.dataBody.active").value(false));
    }

    @Test
    @DisplayName("없는 코드는 404 DISTRICT_001 봉투다")
    void getMissingDistrict() throws Exception {
        mockMvc.perform(get("/api/v1/districts/99999999"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.dataHeader.success").value(false))
            .andExpect(jsonPath("$.dataHeader.resultCode").value("DISTRICT_001"));
    }

    @Test
    @DisplayName("내부 API 는 토큰 없이 {code, name, sigungu, active} 봉투를 준다 — 폐지 코드는 active=false")
    void internalLookup() throws Exception {
        mockMvc.perform(get("/internal/v1/districts/11230510"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dataHeader.success").value(true))
            .andExpect(jsonPath("$.dataBody.code").value("11230510"))
            .andExpect(jsonPath("$.dataBody.name").value("역삼1동"))
            .andExpect(jsonPath("$.dataBody.sigungu").value("서울특별시 강남구"))
            .andExpect(jsonPath("$.dataBody.active").value(true));
        mockMvc.perform(get("/internal/v1/districts/21120560"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dataBody.active").value(false));
    }

    @Test
    @DisplayName("내부 API 도 같은 advice 가 덮는다 — 없는 코드 404 DISTRICT_001, 행안부 10자리 코드 400 DISTRICT_103")
    void internalErrorsAreEnveloped() throws Exception {
        mockMvc.perform(get("/internal/v1/districts/99999999"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("DISTRICT_001"));
        mockMvc.perform(get("/internal/v1/districts/1123051000"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("DISTRICT_103"));
    }
}
