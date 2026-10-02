package com.sneezecast.domainlayer.district.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class DistrictTest {

    @Test
    @DisplayName("시도 · 시군구 표기는 '시도 시군구' 다 — 프론트 District.sigungu 와 같은 모양")
    void sigunguLabelJoinsSidoAndSigungu() {
        assertThat(district("서울특별시", "강남구").sigunguLabel()).isEqualTo("서울특별시 강남구");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  ", "세종특별자치시"})
    @DisplayName("시군구가 없거나(빈 값) 시도와 같으면 시도 이름만 쓴다 — 세종형")
    void sigunguLabelFallsBackToSido(String sigunguName) {
        assertThat(district("세종특별자치시", sigunguName).sigunguLabel()).isEqualTo("세종특별자치시");
    }

    @Test
    @DisplayName("validToYear 가 null 이면 현행, 채워져 있으면 폐지다")
    void activeWhenValidToYearIsNull() {
        assertThat(district("서울특별시", "강남구").isActive()).isTrue();
        assertThat(District.builder().code("21120560").validToYear((short) 2024).build().isActive()).isFalse();
    }

    private static District district(String sidoName, String sigunguName) {
        return District.builder().code("11230510").name("역삼1동").sidoName(sidoName).sigunguName(sigunguName).build();
    }
}
