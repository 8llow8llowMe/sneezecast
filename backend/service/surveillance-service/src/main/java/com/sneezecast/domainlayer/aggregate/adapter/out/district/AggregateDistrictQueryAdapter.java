package com.sneezecast.domainlayer.aggregate.adapter.out.district;

import com.sneezecast.domainlayer.aggregate.application.port.out.AggregateDistrictQueryPort;
import com.sneezecast.domainlayer.aggregate.application.port.out.query.AggregateDistrictQueryResult;
import com.sneezecast.domainlayer.district.application.port.out.DistrictRepositoryPort;
import com.sneezecast.domainlayer.district.domain.model.District;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 같은 서비스의 {@code district} 컨텍스트에 행정동을 묻는다 ({@code ReportDistrictQueryAdapter} 와 같은 방식). district 의 읽기 포트만 쓰고
 * 엔티티 · 리포지토리는 import 하지 않는다. 현행 판정 · 시군구 표기 규칙은 district 도메인 모델에만 둔다.
 *
 * <p>호출자(Facade) 읽기 트랜잭션 안에서 같은 DB 를 읽으므로 외부 I/O 가 아니다.
 */
@Component
@RequiredArgsConstructor
public class AggregateDistrictQueryAdapter implements AggregateDistrictQueryPort {

    private final DistrictRepositoryPort districtRepositoryPort;

    @Override
    public Optional<AggregateDistrictQueryResult> findByCode(String code) {
        return districtRepositoryPort.findByCode(code).map(AggregateDistrictQueryAdapter::toResult);
    }

    @Override
    public List<AggregateDistrictQueryResult> findActiveBySigunguCode(String sigunguCode) {
        return districtRepositoryPort.findActiveBySigunguCode(sigunguCode).stream().map(AggregateDistrictQueryAdapter::toResult).toList();
    }

    private static AggregateDistrictQueryResult toResult(District district) {
        return new AggregateDistrictQueryResult(district.code(), district.name(), district.sigunguLabel(), district.isActive());
    }
}
