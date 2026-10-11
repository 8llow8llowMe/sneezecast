package com.sneezecast.domainlayer.official.domain.model;

import com.sneezecast.domainlayer.official.domain.enums.OfficialAgeGroup;
import com.sneezecast.domainlayer.official.domain.enums.OfficialMetric;
import com.sneezecast.domainlayer.official.domain.enums.OfficialProgram;
import com.sneezecast.domainlayer.official.domain.enums.OfficialRegionLevel;
import com.sneezecast.domainlayer.official.domain.enums.OfficialSource;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Builder;

/**
 * 질병관리청 감시 자료 한 행 (entity-design §3-2). 행은 batch 가 적재하고 surveillance 는 읽기만 한다. <b>시민 자가보고와 섞지 않는다.</b>
 *
 * <p>공식 정보 조회는 주 단위(WEEK) 행만 읽으므로 기간 단위는 싣지 않는다.
 *
 * @param periodYear  질병관리청 연도 (원천 그대로)
 * @param periodWeek  질병관리청 주차 (일요일 시작, 1월 1일이 든 주가 1주 — data-api-analysis §5). ISO 주가 아니다
 * @param metricValue 값 (단위는 {@code metric}). 원천이 빈 칸이면 null — 0 과 다르다
 * @param syncedAt    batch 가 이 값을 마지막으로 쓴 시각 (JVM 기본 시간대의 지역 시각)
 */
@Builder
public record OfficialObservation(
    OfficialSource source,
    OfficialProgram program,
    String diseaseKey,
    String diseaseName,
    String diseaseGroup,
    OfficialMetric metric,
    OfficialAgeGroup ageGroup,
    OfficialRegionLevel regionLevel,
    String regionCode,
    String regionName,
    int periodYear,
    int periodWeek,
    LocalDate periodStart,
    LocalDate periodEnd,
    BigDecimal metricValue,
    LocalDateTime syncedAt
) {

}
