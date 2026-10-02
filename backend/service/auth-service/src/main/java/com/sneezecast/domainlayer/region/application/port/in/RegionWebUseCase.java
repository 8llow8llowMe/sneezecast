package com.sneezecast.domainlayer.region.application.port.in;

import com.sneezecast.domainlayer.region.adapter.in.web.dto.response.MemberRegionResponse;

public interface RegionWebUseCase {

    /** 현행 행정동인지 확인한 뒤 회원당 1행으로 저장(upsert)한다. 확인하지 못하면 저장하지 않는다. */
    MemberRegionResponse saveMyRegion(long memberId, String districtCode);

    /** 아직 고르지 않았으면 null (부모당 0~1개 하위 리소스 — architecture-guide §8). */
    MemberRegionResponse getMyRegion(long memberId);
}
