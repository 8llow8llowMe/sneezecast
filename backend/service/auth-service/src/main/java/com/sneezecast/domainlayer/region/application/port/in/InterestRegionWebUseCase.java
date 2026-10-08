package com.sneezecast.domainlayer.region.application.port.in;

import com.sneezecast.domainlayer.region.adapter.in.web.dto.response.MemberRegionResponse;
import com.sneezecast.persistence.dto.SliceResponse;

/** 관심 동네. 세 요청 모두 바뀐 뒤의 목록을 고른 순서로 돌려준다 — 상한이 작아 한 번에 모두 주고 {@code hasNext} 는 항상 false 다. */
public interface InterestRegionWebUseCase {

    SliceResponse<MemberRegionResponse> getMyInterestRegions(long memberId);

    /** 현행 행정동인지 확인한 뒤 더한다. 확인하지 못하면 더하지 않는다. */
    SliceResponse<MemberRegionResponse> addMyInterestRegion(long memberId, String districtCode);

    /** 목록에 없어도 성공이다(멱등). */
    SliceResponse<MemberRegionResponse> removeMyInterestRegion(long memberId, String districtCode);
}
