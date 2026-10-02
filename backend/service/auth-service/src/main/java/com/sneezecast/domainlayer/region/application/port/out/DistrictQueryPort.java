package com.sneezecast.domainlayer.region.application.port.out;

import com.sneezecast.domainlayer.region.application.exception.RegionException;
import com.sneezecast.domainlayer.region.application.port.out.query.DistrictQueryResult;
import java.util.Optional;

/**
 * 행정동 조회 — 정본은 surveillance {@code district} 다 (architecture-guide §4 {@code GET /internal/v1/districts/{code}}).
 *
 * <p>원격 호출이라 <b>트랜잭션 안에서 부르지 않는다</b> (architecture-guide §3-1).
 */
public interface DistrictQueryPort {

    /**
     * @param code 형식 검증(숫자 8자리)을 마친 코드
     * @return 행정동. 폐지된 코드도 {@code active=false} 로 돌려준다. 없는 코드면 empty
     * @throws RegionException {@code INTERNAL_SERVICE_UNAVAILABLE}(503) — 상대가 응답하지 못했다(5xx · timeout · 서킷 오픈) 또는 계약에 없는
     *                         응답을 받았다
     */
    Optional<DistrictQueryResult> findByCode(String code);
}
