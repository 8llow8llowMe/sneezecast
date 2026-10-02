package com.sneezecast.domainlayer.report.application.port.out.query;

/**
 * 보고 행정동 확인 결과. 보고에 필요한 것은 현행 여부뿐이라 이름 · 시군구는 싣지 않는다.
 *
 * @param active 현행 행정동이면 true, 폐지된 코드면 false
 */
public record ReportDistrictQueryResult(
    String code,
    boolean active
) {

}
