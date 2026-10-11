package com.sneezecast.domainlayer.advisory.application.service;

import com.sneezecast.domainlayer.advisory.adapter.in.web.dto.response.RegionAdvisoryResponse;
import com.sneezecast.domainlayer.advisory.application.port.in.AdvisoryWebUseCase;
import com.sneezecast.domainlayer.advisory.application.service.presenter.AdvisoryPresenter;
import com.sneezecast.domainlayer.advisory.application.service.processor.AdvisoryPublicQueryProcessor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 공개 동네 안내. 회원 확인이 없다(비회원 둘러보기). DB 읽기뿐이라 Facade 에 읽기 트랜잭션을 건다 (architecture-guide §3-1) — 행정동 확인과 안내를 한
 * 트랜잭션에서 읽는다. 잠그지 않으므로 운영자 전이와 줄을 서지 않는다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdvisoryWebFacade implements AdvisoryWebUseCase {

    private final AdvisoryPublicQueryProcessor advisoryPublicQueryProcessor;
    private final AdvisoryPresenter advisoryPresenter;

    @Override
    public RegionAdvisoryResponse getRegionAdvisory(String districtCode, String week) {
        return advisoryPublicQueryProcessor.getRegionAdvisory(districtCode, week).map(advisoryPresenter::toResponse).orElse(null);
    }
}
