package com.sneezecast.domainlayer.region.application.service;

import com.sneezecast.domainlayer.region.adapter.in.web.dto.response.MemberRegionResponse;
import com.sneezecast.domainlayer.region.application.exception.RegionErrorCode;
import com.sneezecast.domainlayer.region.application.exception.RegionException;
import com.sneezecast.domainlayer.region.application.info.MemberRegionInfo;
import com.sneezecast.domainlayer.region.application.port.in.InterestRegionWebUseCase;
import com.sneezecast.domainlayer.region.application.port.out.query.DistrictQueryResult;
import com.sneezecast.domainlayer.region.application.service.presenter.RegionPresenter;
import com.sneezecast.domainlayer.region.application.service.processor.MemberInterestRegionProcessor;
import com.sneezecast.domainlayer.region.application.service.processor.MemberRegionProcessor;
import com.sneezecast.domainlayer.region.domain.model.MemberInterestRegion;
import com.sneezecast.persistence.dto.SliceResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 관심 동네 조회 · 추가 · 삭제 오케스트레이션. <b>이 Facade 에는 트랜잭션을 걸지 않는다</b> — {@code RegionWebFacade} 와 같은 이유로, 원격 구간
 * (행정동 확인 · 이름 조회 — {@link MemberRegionProcessor})과 DB 구간({@link MemberInterestRegionProcessor} 의 메서드 트랜잭션)을 나눠 부른다
 * (architecture-guide §3-1).
 *
 * <p><b>변경은 커밋된 채 503 일 수 있다.</b> 추가 · 삭제를 커밋한 뒤 목록 이름을 읽다 surveillance 가 실패하면 응답은 {@code REGION_004} 지만
 * 변경은 남는다. 받아들인다 — 삭제는 멱등이라 다시 보내면 되고, 추가를 다시 보내면 {@code REGION_006}(이미 고름)이 되어 화면이 GET 으로
 * 목록을 다시 읽는다.
 */
@Service
@RequiredArgsConstructor
public class InterestRegionWebFacade implements InterestRegionWebUseCase {

    private final MemberInterestRegionProcessor memberInterestRegionProcessor;
    private final MemberRegionProcessor memberRegionProcessor;
    private final RegionPresenter regionPresenter;

    /** 저장된 코드 조회(DB, 읽기 트랜잭션) → 동네마다 이름 · 폐지 여부 조회(원격, 트랜잭션 없음). */
    @Override
    public SliceResponse<MemberRegionResponse> getMyInterestRegions(long memberId) {
        return present(memberInterestRegionProcessor.findByMemberId(memberId), null);
    }

    /**
     * 행정동 확인(원격, 트랜잭션 없음) → 추가(DB 트랜잭션) → 목록 이름 조회(원격). 확인이 실패하면(없음 · 폐지 · 장애) 예외로 끝나 DB 구간에 닿지
     * 않는다. 동시 추가로 막혀 {@code REGION_003} 이 오면 그 트랜잭션은 이미 롤백됐고, 새 읽기 트랜잭션에서 무엇과 겹쳤는지 다시 가른다.
     */
    @Override
    public SliceResponse<MemberRegionResponse> addMyInterestRegion(long memberId, String districtCode) {
        DistrictQueryResult district = memberRegionProcessor.requireSelectableDistrict(districtCode);
        List<MemberInterestRegion> regions;
        try {
            regions = memberInterestRegionProcessor.add(memberId, district.code());
        } catch (RegionException exception) {
            if (exception.getErrorCode() == RegionErrorCode.REGION_SAVE_CONFLICT) {
                throw memberInterestRegionProcessor.reclassifyAddConflict(memberId, district.code(), exception);
            }
            throw exception;
        }
        return present(regions, district);
    }

    /** 삭제(DB 트랜잭션, 없어도 성공) → 남은 목록 이름 조회(원격). */
    @Override
    public SliceResponse<MemberRegionResponse> removeMyInterestRegion(long memberId, String districtCode) {
        return present(memberInterestRegionProcessor.remove(memberId, districtCode), null);
    }

    /**
     * 동네마다 surveillance 를 한 번씩 <b>순차로</b> 부른다 — 벌크 내부 API 가 없고, 상한(region.interest.max-count, 기본 3)만큼만 부르므로
     * 감수한다(coding-conventions §8-5 의 예외). 하나라도 장애면 {@code REGION_004}(503)로 끝난다 — 이름을 지어내지 않는다.
     *
     * @param checked 이 요청에서 방금 확인한 행정동 (없으면 null). 그 코드는 다시 부르지 않는다
     */
    private SliceResponse<MemberRegionResponse> present(List<MemberInterestRegion> regions, DistrictQueryResult checked) {
        List<MemberRegionInfo> infos = regions.stream()
            .map(region -> checked != null && checked.code().equals(region.districtCode())
                ? memberRegionProcessor.toInfo(checked)
                : memberRegionProcessor.describe(region.districtCode()))
            .toList();
        return regionPresenter.toSliceResponse(infos);
    }
}
