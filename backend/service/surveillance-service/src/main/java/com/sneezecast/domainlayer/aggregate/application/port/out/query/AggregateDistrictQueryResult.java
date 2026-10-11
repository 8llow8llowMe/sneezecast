package com.sneezecast.domainlayer.aggregate.application.port.out.query;

/**
 * 집계 응답에 붙이는 행정동 정보. district 컨텍스트의 도메인 모델을 aggregate 로 들이지 않으려고 필요한 값만 옮긴다.
 *
 * @param code    SGIS 행정동 코드 8자리
 * @param name    행정동 이름
 * @param sigungu 시도 · 시군구 표기 (district 의 {@code sigunguLabel} 규칙)
 * @param active  현행이면 true, 폐지된 코드면 false
 */
public record AggregateDistrictQueryResult(
    String code,
    String name,
    String sigungu,
    boolean active
) {

}
