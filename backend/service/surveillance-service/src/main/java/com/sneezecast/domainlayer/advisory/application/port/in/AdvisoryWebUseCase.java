package com.sneezecast.domainlayer.advisory.application.port.in;

import com.sneezecast.domainlayer.advisory.adapter.in.web.dto.response.RegionAdvisoryResponse;

/**
 * 공개 동네 안내 조회 (#215). 비회원도 부른다. 인자는 웹 경계가 형식을 확인한 값이다.
 */
public interface AdvisoryWebUseCase {

    /**
     * 동네 하나 · 주 하나의 발행 중인 안내 또는 철회 표시. 안내가 없으면 null.
     * 없는 코드면 {@code ADVISORY_008}(404), 조회할 수 없는 주면 {@code ADVISORY_009}(400).
     */
    RegionAdvisoryResponse getRegionAdvisory(String districtCode, String week);
}
