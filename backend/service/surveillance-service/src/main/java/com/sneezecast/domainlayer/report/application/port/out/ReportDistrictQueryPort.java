package com.sneezecast.domainlayer.report.application.port.out;

import com.sneezecast.domainlayer.report.application.port.out.query.ReportDistrictQueryResult;
import java.util.Optional;

/**
 * 보고 행정동 확인 포트. 행정동 마스터는 같은 서비스의 {@code district} 컨텍스트가 소유하므로, report 는 그 영속 계층을 직접 부르지 않고 이 포트로만 묻는다.
 */
public interface ReportDistrictQueryPort {

    /** 폐지 여부와 무관하게 코드로 찾는다. 없으면 빈 값. */
    Optional<ReportDistrictQueryResult> findByCode(String districtCode);
}
