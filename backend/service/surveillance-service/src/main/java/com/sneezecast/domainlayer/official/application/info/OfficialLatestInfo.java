package com.sneezecast.domainlayer.official.application.info;

import com.sneezecast.domainlayer.official.domain.enums.OfficialAgeGroup;
import com.sneezecast.domainlayer.official.domain.enums.OfficialMetric;
import com.sneezecast.domainlayer.official.domain.enums.OfficialProgram;
import com.sneezecast.domainlayer.official.domain.enums.OfficialRegionLevel;
import com.sneezecast.domainlayer.official.domain.enums.OfficialSource;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 질병관리청 감시 자료의 최신 기준 주 — 프로그램 → 감염병(계열) → 연령대 값. <b>시민 자가보고 값은 여기에 없다.</b>
 *
 * @param programs 자료가 있는 프로그램만, {@link OfficialProgram} 선언 순서. 자료가 하나도 없으면 빈 목록
 */
public record OfficialLatestInfo(List<Program> programs) {

    /**
     * @param sourceUrl 원문 주소. 확인된 주소가 없으면 null ({@code OfficialSourceLink})
     * @param diseases  감염병 키 → 지표 → 지역 순
     */
    public record Program(OfficialProgram program, OfficialSource source, String sourceUrl, List<Disease> diseases) {

    }

    /**
     * 한 계열의 최신 기준 주. 기간 · 지역은 그 주의 모든 연령대 행이 같다.
     *
     * @param periodYear 질병관리청 연도 · 주차 (ISO 주가 아니다)
     * @param syncedAt   이 주 행들의 마지막 적재 시각 중 가장 늦은 것 (JVM 기본 시간대의 지역 시각)
     * @param values     연령대 선언 순서 ({@code ALL} 먼저)
     */
    public record Disease(
        String diseaseKey,
        String diseaseName,
        String diseaseGroup,
        OfficialMetric metric,
        OfficialRegionLevel regionLevel,
        String regionCode,
        String regionName,
        int periodYear,
        int periodWeek,
        LocalDate periodStart,
        LocalDate periodEnd,
        LocalDateTime syncedAt,
        List<Value> values
    ) {

    }

    /** @param value 원천이 빈 칸이면 null — 0 과 다르다 */
    public record Value(OfficialAgeGroup ageGroup, BigDecimal value) {

    }
}
