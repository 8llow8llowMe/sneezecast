package com.sneezecast.domainlayer.aggregate.application.port.out;

import com.sneezecast.domainlayer.aggregate.application.port.out.query.AggregateDistrictQueryResult;
import java.util.List;
import java.util.Optional;

/**
 * 공개 집계 조회가 행정동을 묻는 포트. 행정동은 같은 서비스의 {@code district} 컨텍스트가 소유하므로 aggregate 는 그 영속 계층을 직접 부르지 않는다.
 */
public interface AggregateDistrictQueryPort {

    /** 폐지 여부와 무관하게 코드로 찾는다. */
    Optional<AggregateDistrictQueryResult> findByCode(String code);

    /** 시군구 안의 현행 행정동, 코드 오름차순. */
    List<AggregateDistrictQueryResult> findActiveBySigunguCode(String sigunguCode);
}
