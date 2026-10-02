package com.sneezecast.domainlayer.district.domain.model;

import lombok.Builder;

/**
 * 행정동 (SGIS 읍면동, entity-design §3-1). 행은 batch 가 적재하고 surveillance 는 읽기만 한다.
 *
 * @param code        SGIS 읍면동 코드 8자리 ({@code adm_cd}). 행안부 10자리 코드와 체계가 다르다
 * @param name        읍면동 이름 (예: 역삼1동)
 * @param sidoName    시도 이름 (예: 서울특별시)
 * @param sigunguName 시군구 이름 (예: 강남구). 시군구가 없는 시도는 비었거나 시도 이름과 같을 수 있다
 * @param validToYear 폐지 직전 SGIS 기준 연도. null 이면 현행
 */
@Builder
public record District(
    String code,
    String name,
    String sidoName,
    String sigunguName,
    Short validToYear
) {

    private static final String LABEL_DELIMITER = " ";

    /** 현행 행정동인지. 폐지된 동도 지우지 않고 {@code validToYear} 만 채운다 — 과거 보고 · 집계 행이 그 코드를 참조한다. */
    public boolean isActive() {
        return validToYear == null;
    }

    /**
     * 화면이 이름이 같은 동을 가려 보이는 시도 · 시군구 표기 (예: {@code 서울특별시 강남구}). 프론트 {@code District.sigungu} 와 같은 모양이다.
     *
     * <p>세종처럼 시군구가 없는 시도는 SGIS 가 시군구 이름을 비우거나 시도 이름을 그대로 줄 수 있어, 그때는 시도 이름만 쓴다
     * ({@code 세종특별자치시 세종특별자치시} 로 겹쳐 보이지 않게). 이 조립 규칙은 여기 한 곳에만 둔다.
     */
    public String sigunguLabel() {
        if (sigunguName == null || sigunguName.isBlank() || sigunguName.equals(sidoName)) {
            return sidoName;
        }
        return sidoName + LABEL_DELIMITER + sigunguName;
    }
}
