package com.sneezecast.domainlayer.sentinelimport.domain.model;

import com.sneezecast.domainlayer.official.domain.enums.OfficialAgeGroup;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * 인플루엔자 의사환자 분율 한 칸 (한 주 × 한 연령대, 외래 1,000명당). 원천 한 행(한 연령대)이 열 수(주 수)만큼의 이 행이 된다
 * (entity-design §3-2).
 *
 * <p>연도는 열 제목의 주차만 보고 정하지 않는다 — 절기 36 ~ 52(53)주는 시작 연도, 01 ~ 35주는 끝 연도다. 원천 어댑터가 {@code GR2} 로 가른다.
 *
 * @param year     원천 연도 (절기 시작 연도 또는 끝 연도)
 * @param week     원천 주차 (질병관리청 주차, 1 ~ 53)
 * @param ageGroup 연령대. 원천은 연령대별 7행만 주고 전체 합계 행이 없다
 * @param value    의사환자 분율. 원천이 비우거나 음수({@code -} 로 그린다)면 null (0 과 구분한다)
 */
public record SentinelIliRow(int year, int week, OfficialAgeGroup ageGroup, BigDecimal value) {

    public SentinelIliRow {
        SentinelRows.requireWeek(week);
        Objects.requireNonNull(ageGroup, "ageGroup must not be null");
        SentinelRows.requireValue(value);
    }
}
