package com.sneezecast.domainlayer.report.application.port.out.query;

import com.sneezecast.domainlayer.report.domain.enums.SymptomGroup;
import java.util.Set;

/**
 * 주 하나의 보고를 행정동 × 증상군 조합으로 센 한 줄 (집계 재계산용). 보고자 · 보고 행은 싣지 않는다 — 집계는 이 수만 본다.
 *
 * @param districtCode       보고 행정동 코드
 * @param symptoms           증상군 조합. 빈 집합 = 증상 없음
 * @param reportCount        이 조합의 보고 수 (= 보고자 수, 같은 사람의 같은 주는 한 행이다)
 * @param revisedReportCount 그중 한 번 이상 수정된 보고 수
 */
public record WeeklyReportCountQueryResult(
    String districtCode,
    Set<SymptomGroup> symptoms,
    int reportCount,
    int revisedReportCount
) {

}
