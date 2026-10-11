package com.sneezecast.domainlayer.advisory.adapter.out.district;

import com.sneezecast.domainlayer.advisory.application.port.out.AdvisoryDistrictQueryPort;
import com.sneezecast.domainlayer.district.application.port.out.DistrictRepositoryPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 같은 서비스의 {@code district} 컨텍스트에 행정동을 묻는다 ({@code AggregateDistrictQueryAdapter} 와 같은 방식). district 의 읽기 포트만 쓰고
 * 엔티티 · 리포지토리는 import 하지 않는다.
 *
 * <p>호출자(Facade) 읽기 트랜잭션 안에서 같은 DB 를 읽으므로 외부 I/O 가 아니다.
 */
@Component
@RequiredArgsConstructor
public class AdvisoryDistrictQueryAdapter implements AdvisoryDistrictQueryPort {

    private final DistrictRepositoryPort districtRepositoryPort;

    @Override
    public boolean existsByCode(String code) {
        return districtRepositoryPort.findByCode(code).isPresent();
    }
}
