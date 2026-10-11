package com.sneezecast.domainlayer.aggregate.application.service;

import com.sneezecast.domainlayer.aggregate.adapter.in.web.dto.response.DistrictWeeklyAggregateListResponse;
import com.sneezecast.domainlayer.aggregate.adapter.in.web.dto.response.DistrictWeeklyAggregateResponse;
import com.sneezecast.domainlayer.aggregate.application.port.in.AggregateWebUseCase;
import com.sneezecast.domainlayer.aggregate.application.service.presenter.AggregatePresenter;
import com.sneezecast.domainlayer.aggregate.application.service.processor.AggregateQueryProcessor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 공개 집계 조회. 회원 확인이 없다(비회원 둘러보기 · 지도). DB 읽기뿐이라 Facade 에 읽기 트랜잭션을 건다 (architecture-guide §3-1) — 행정동 · 이번 주 ·
 * 전주 · 마감 여부를 한 스냅샷에서 읽는다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AggregateWebFacade implements AggregateWebUseCase {

    private final AggregateQueryProcessor aggregateQueryProcessor;
    private final AggregatePresenter aggregatePresenter;

    @Override
    public DistrictWeeklyAggregateResponse getDistrictWeeklyAggregate(String code, String week) {
        return aggregatePresenter.toResponse(aggregateQueryProcessor.getDistrictWeek(code, week));
    }

    @Override
    public DistrictWeeklyAggregateListResponse getDistrictWeeklyAggregates(String sigunguCode, String include, String week) {
        return aggregatePresenter.toListResponse(aggregateQueryProcessor.getSigunguWeek(sigunguCode, include, week));
    }
}
