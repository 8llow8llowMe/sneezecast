package com.sneezecast.domainlayer.sentinelimport.domain.model;

import java.math.BigDecimal;

/**
 * 급성호흡기 · 장관감염증 한 칸 (한 주 × 한 병원체). 원천 한 행(한 주)이 열 수만큼의 이 행이 된다. 테이블 구조의 정본은 surveillance-service 의
 * {@code OfficialSurveillanceEntity} 다 (entity-design §3-2).
 *
 * <p>생성 시 주차 범위, 이름, 컬럼 길이(이름 100자 · 분류 20자), 값 범위(DECIMAL(12,2), 0 이상)를 검사한다. 어기면
 * {@link IllegalArgumentException} 이고, 원천 어댑터가 응답 해석 실패로 바꾼다.
 *
 * @param year         원천 연도
 * @param week         원천 주차 (질병관리청 주차, 1 ~ 53)
 * @param diseaseKey   포털 병원체 코드 (합계는 {@link SentinelPathogenCatalog#TOTAL_KEY})
 * @param diseaseName  병원체 이름 (원천 {@code SUBTITLE} 그대로)
 * @param diseaseGroup 원천 분류 (원천 {@code TITLE} 그대로 — 세균 · 바이러스 · 원충 · 계 …)
 * @param value        신고 수. 원천이 비우거나 음수({@code -} 로 그린다)면 null (0 과 구분한다)
 */
public record SentinelPathogenRow(int year, int week, String diseaseKey, String diseaseName, String diseaseGroup, BigDecimal value) {

    public SentinelPathogenRow {
        SentinelRows.requireWeek(week);
        SentinelRows.requireText(diseaseKey, "diseaseKey", SentinelRows.MAX_DISEASE_LENGTH);
        SentinelRows.requireText(diseaseName, "diseaseName", SentinelRows.MAX_DISEASE_LENGTH);
        SentinelRows.requireText(diseaseGroup, "diseaseGroup", SentinelRows.MAX_DISEASE_GROUP_LENGTH);
        SentinelRows.requireValue(value);
    }
}
