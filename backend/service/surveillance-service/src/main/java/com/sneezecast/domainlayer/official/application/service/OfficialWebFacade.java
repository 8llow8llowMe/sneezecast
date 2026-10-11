package com.sneezecast.domainlayer.official.application.service;

import com.sneezecast.domainlayer.official.adapter.in.web.dto.response.OfficialLatestResponse;
import com.sneezecast.domainlayer.official.application.port.in.OfficialWebUseCase;
import com.sneezecast.domainlayer.official.application.service.presenter.OfficialPresenter;
import com.sneezecast.domainlayer.official.application.service.processor.OfficialQueryProcessor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 공식 정보 공개 조회. 비회원도 보므로 회원 확인이 없다. DB 읽기뿐이라 Facade 에 읽기 트랜잭션을 건다 (architecture-guide §3-1) — 원천 호출은
 * batch 가 하고 여기서는 적재된 값만 읽는다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OfficialWebFacade implements OfficialWebUseCase {

    private final OfficialQueryProcessor officialQueryProcessor;
    private final OfficialPresenter officialPresenter;

    @Override
    public OfficialLatestResponse getLatest() {
        return officialPresenter.toLatestResponse(officialQueryProcessor.getLatest());
    }
}
