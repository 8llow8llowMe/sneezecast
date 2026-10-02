package com.sneezecast.domainlayer.region.application.service.presenter;

import com.sneezecast.domainlayer.region.adapter.in.web.dto.response.MemberRegionResponse;
import com.sneezecast.domainlayer.region.application.info.MemberRegionInfo;
import org.springframework.stereotype.Component;

@Component
public class RegionPresenter {

    public MemberRegionResponse toResponse(MemberRegionInfo info) {
        return MemberRegionResponse.builder()
            .code(info.code())
            .name(info.name())
            .sigungu(info.sigungu())
            .abolished(info.abolished())
            .build();
    }
}
