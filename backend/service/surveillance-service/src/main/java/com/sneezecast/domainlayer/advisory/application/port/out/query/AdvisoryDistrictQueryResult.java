package com.sneezecast.domainlayer.advisory.application.port.out.query;

/**
 * 검토 후보(#266)가 보이는 행정동 — 이름과 같은 이름의 동을 가르는 시도 · 시군구 표기({@code District#sigunguLabel}).
 *
 * @param active 현행이면 true, 폐지된 코드면 false
 */
public record AdvisoryDistrictQueryResult(
    String code,
    String name,
    String sigungu,
    boolean active
) {

}
