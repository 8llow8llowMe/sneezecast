package com.sneezecast.domainlayer.region.application.service;

import com.sneezecast.domainlayer.region.adapter.in.web.dto.response.MemberRegionResponse;
import com.sneezecast.domainlayer.region.application.port.in.RegionWebUseCase;
import com.sneezecast.domainlayer.region.application.port.out.query.DistrictQueryResult;
import com.sneezecast.domainlayer.region.application.service.presenter.RegionPresenter;
import com.sneezecast.domainlayer.region.application.service.processor.MemberRegionProcessor;
import com.sneezecast.domainlayer.region.domain.model.MemberRegion;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 내 동네 저장 · 조회 오케스트레이션. <b>이 Facade 에는 트랜잭션을 걸지 않는다</b> — 두 흐름 모두 surveillance 원격 조회가 섞여 있어, Facade 에
 * 걸면 커넥션을 쥔 채 원격 응답(최대 read timeout)을 기다린다. 원격 조회를 먼저 끝내고 DB 구간은 {@link MemberRegionProcessor} 의 메서드
 * 트랜잭션으로 좁힌다 (architecture-guide §3-1).
 */
@Service
@RequiredArgsConstructor
public class RegionWebFacade implements RegionWebUseCase {

    private final MemberRegionProcessor memberRegionProcessor;
    private final RegionPresenter regionPresenter;

    /**
     * 행정동 확인(원격, 트랜잭션 없음) → upsert(DB 트랜잭션). 확인이 실패하면(없음 · 폐지 · 장애) 예외로 끝나 저장 구간에 닿지 않는다.
     * 응답의 이름은 방금 확인한 값이다 — 저장 뒤 다시 부르지 않는다.
     */
    @Override
    public MemberRegionResponse saveMyRegion(long memberId, String districtCode) {
        DistrictQueryResult district = memberRegionProcessor.requireSelectableDistrict(districtCode);
        memberRegionProcessor.save(memberId, district.code());
        return regionPresenter.toResponse(memberRegionProcessor.toInfo(district));
    }

    /** 저장된 코드 조회(DB, 읽기 트랜잭션) → 이름 · 폐지 여부 조회(원격, 트랜잭션 없음). */
    @Override
    public MemberRegionResponse getMyRegion(long memberId) {
        Optional<MemberRegion> region = memberRegionProcessor.findByMemberId(memberId);
        return region.map(saved -> regionPresenter.toResponse(memberRegionProcessor.describe(saved.districtCode()))).orElse(null);
    }
}
