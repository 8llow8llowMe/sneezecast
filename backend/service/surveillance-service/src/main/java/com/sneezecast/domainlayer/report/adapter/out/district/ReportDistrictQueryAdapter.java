package com.sneezecast.domainlayer.report.adapter.out.district;

import com.sneezecast.domainlayer.district.application.port.out.DistrictRepositoryPort;
import com.sneezecast.domainlayer.report.application.port.out.ReportDistrictQueryPort;
import com.sneezecast.domainlayer.report.application.port.out.query.ReportDistrictQueryResult;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 같은 서비스의 {@code district} 컨텍스트에 행정동을 묻는다. district 의 읽기 포트만 쓰고 엔티티 · 리포지토리는 import 하지 않는다 — 행정동
 * 테이블 구조가 바뀌어도 report 는 이 어댑터만 본다. 현행 판정 규칙({@code valid_to_year} null)은 district 도메인 모델에만 둔다.
 *
 * <p>호출자(Processor) 트랜잭션 안에서 같은 DB 를 읽으므로 외부 I/O 가 아니다.
 */
@Component
@RequiredArgsConstructor
public class ReportDistrictQueryAdapter implements ReportDistrictQueryPort {

    private final DistrictRepositoryPort districtRepositoryPort;

    @Override
    public Optional<ReportDistrictQueryResult> findByCode(String districtCode) {
        return districtRepositoryPort.findByCode(districtCode)
            .map(district -> new ReportDistrictQueryResult(district.code(), district.isActive()));
    }
}
