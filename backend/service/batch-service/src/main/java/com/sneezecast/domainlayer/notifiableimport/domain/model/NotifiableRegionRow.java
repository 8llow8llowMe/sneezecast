package com.sneezecast.domainlayer.notifiableimport.domain.model;

import java.math.BigDecimal;

/**
 * {@code /Region} 시도 연별 한 행 (함께 오는 전국 행은 원천 어댑터가 버린다). 지표(발생 수 · 10만 명당)는 요청이 정하므로 행에 두지 않는다.
 *
 * <p>생성 시 시도 코드, 이름, 컬럼 길이(시도 이름 30자 · 감염병 이름 100자 · 분류 20자), 값 범위(DECIMAL(12,2), 0 이상)를 검사한다. 어기면 {@link IllegalArgumentException} 이고, 원천 어댑터가 응답 해석 실패로 바꾼다.
 *
 * @param year         원천 연도
 * @param sidoCode     질병관리청 시도 코드 (SGIS 코드와 다르다)
 * @param sidoName     시도 이름 (원천 그대로 — 같은 코드라도 출처마다 표기가 다르다)
 * @param diseaseKey   감염병 이름에서 앞의 {@code @} 표식을 뗀 값
 * @param diseaseName  감염병 이름 (원천 그대로)
 * @param diseaseGroup {@code 제N급} 으로 맞춘 분류. 원천이 비우면 null
 * @param value        값. 원천이 비우거나 숫자가 아니면 null
 */
public record NotifiableRegionRow(int year, String sidoCode, String sidoName, String diseaseKey, String diseaseName, String diseaseGroup,
                                  BigDecimal value) {

    public NotifiableRegionRow {
        NotifiableRequest.requireSidoCode(sidoCode);
        NotifiableRows.requireText(sidoName, "sidoName", NotifiableRows.MAX_REGION_NAME_LENGTH);
        NotifiableRows.requireText(diseaseKey, "diseaseKey", NotifiableRows.MAX_DISEASE_LENGTH);
        NotifiableRows.requireText(diseaseName, "diseaseName", NotifiableRows.MAX_DISEASE_LENGTH);
        NotifiableRows.requireMaxLength(diseaseGroup, "diseaseGroup", NotifiableRows.MAX_DISEASE_GROUP_LENGTH);
        NotifiableRows.requireValue(value);
    }
}
