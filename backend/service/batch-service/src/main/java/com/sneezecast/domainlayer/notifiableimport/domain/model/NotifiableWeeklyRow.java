package com.sneezecast.domainlayer.notifiableimport.domain.model;

import java.math.BigDecimal;

/**
 * {@code /PeriodBasic} 주별 전국 한 행 ({@code 계} 행은 원천 어댑터가 버린다). 테이블 구조의 정본은 surveillance-service 의
 * {@code OfficialSurveillanceEntity} 다 (entity-design §3-2).
 *
 * <p>생성 시 주차 범위, 이름, 컬럼 길이(이름 100자 · 분류 20자), 값 범위(DECIMAL(12,2), 0 이상)를 검사한다. 어기면 {@link IllegalArgumentException} 이고, 원천 어댑터가 응답 해석 실패로 바꾼다.
 *
 * @param periodYear   원천 연도
 * @param periodWeek   원천 주차 (질병관리청 주차, 1 ~ 53)
 * @param diseaseKey   감염병 이름에서 앞의 {@code @} 표식을 뗀 값
 * @param diseaseName  감염병 이름 (원천 그대로, {@code @} 포함)
 * @param diseaseGroup {@code 제N급} 으로 맞춘 분류. 원천이 비우면 null
 * @param value        발생 수. 원천이 비우거나 숫자가 아니면 null (0 과 구분한다)
 */
public record NotifiableWeeklyRow(int periodYear, int periodWeek, String diseaseKey, String diseaseName, String diseaseGroup, BigDecimal value) {

    public static final int MIN_WEEK = 1;
    public static final int MAX_WEEK = 53;

    public NotifiableWeeklyRow {
        if (periodWeek < MIN_WEEK || periodWeek > MAX_WEEK) {
            throw new IllegalArgumentException("periodWeek must be between 1 and 53. periodWeek=" + periodWeek);
        }
        NotifiableRows.requireText(diseaseKey, "diseaseKey", NotifiableRows.MAX_DISEASE_LENGTH);
        NotifiableRows.requireText(diseaseName, "diseaseName", NotifiableRows.MAX_DISEASE_LENGTH);
        NotifiableRows.requireMaxLength(diseaseGroup, "diseaseGroup", NotifiableRows.MAX_DISEASE_GROUP_LENGTH);
        NotifiableRows.requireValue(value);
    }
}
