package com.sneezecast.domainlayer.notifiableimport.domain.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 시도별 연간 값({@code /Region})의 지표 — 원천 파라미터 {@code searchType} (data-api-analysis §2).
 *
 * <p>{@code keyName} 은 적재 이력 {@code request_key} 에 쓰는 짧은 이름이다. 이력의 키라 바꾸면 과거 이력과 이어지지 않는다.
 */
@Getter
@RequiredArgsConstructor
public enum NotifiableRegionMeasure {

    /** 발생 수. */
    CASE_COUNT(1, "count"),
    /** 인구 10만 명당 발생률. */
    INCIDENCE_PER_100K(2, "per100k");

    private final int searchType;
    private final String keyName;
}
