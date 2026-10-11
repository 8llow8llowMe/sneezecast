package com.sneezecast.domainlayer.advisory.application.port.out;

import com.sneezecast.domainlayer.advisory.application.port.out.query.AdvisoryDistrictQueryResult;
import java.util.Collection;
import java.util.List;

/**
 * 공개 동네 안내 · 검토 후보가 행정동을 묻는 포트. 행정동은 같은 서비스의 {@code district} 컨텍스트가 소유하므로 advisory 는 그 영속 계층을 직접 부르지
 * 않는다.
 */
public interface AdvisoryDistrictQueryPort {

    /** 폐지 여부와 무관하게 코드가 있는지. 폐지된 코드도 true 다 — 행정동 조회(#111) · 공개 집계(#212)와 같은 관례. */
    boolean existsByCode(String code);

    /** 검토 후보(#266) · 발행 이력(#267) — 여러 코드의 행정동을 한 번에(폐지 포함). 없는 코드는 결과에 없고 순서는 정하지 않는다. 코드가 비면 빈 목록이다. */
    List<AdvisoryDistrictQueryResult> findAllByCodeIn(Collection<String> codes);
}
