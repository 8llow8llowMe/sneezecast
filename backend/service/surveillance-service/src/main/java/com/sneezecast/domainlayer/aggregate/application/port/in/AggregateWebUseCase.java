package com.sneezecast.domainlayer.aggregate.application.port.in;

import com.sneezecast.domainlayer.aggregate.adapter.in.web.dto.response.DistrictWeeklyAggregateListResponse;
import com.sneezecast.domainlayer.aggregate.adapter.in.web.dto.response.DistrictWeeklyAggregateResponse;

/**
 * 공개 집계 조회 (홈 · 지도). 비회원도 부른다. 인자는 웹 경계가 형식을 확인한 값이다.
 */
public interface AggregateWebUseCase {

    /**
     * 동네 하나의 한 주. {@code week} 가 null 이면 현재 주(KST).
     * 없는 코드면 {@code AGGREGATE_001}(404), 조회할 수 없는 주면 {@code AGGREGATE_002}(400).
     */
    DistrictWeeklyAggregateResponse getDistrictWeeklyAggregate(String code, String week);

    /**
     * 시군구 하나의 현행 행정동 전부 + {@code include}(선택, 반드시 담는다)의 한 주.
     * {@code include} 가 없는 코드면 {@code AGGREGATE_001}(404), 조회할 수 없는 주면 {@code AGGREGATE_002}(400).
     */
    DistrictWeeklyAggregateListResponse getDistrictWeeklyAggregates(String sigunguCode, String include, String week);
}
