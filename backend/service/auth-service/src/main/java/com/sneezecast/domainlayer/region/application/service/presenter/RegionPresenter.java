package com.sneezecast.domainlayer.region.application.service.presenter;

import com.sneezecast.domainlayer.region.adapter.in.web.dto.response.MemberRegionResponse;
import com.sneezecast.domainlayer.region.application.info.MemberRegionInfo;
import com.sneezecast.persistence.dto.SliceResponse;
import java.util.List;
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

    /** 관심 동네 목록. 상한(region.interest.max-count)만큼만 있어 한 번에 모두 주므로 {@code hasNext} 는 항상 false 다. */
    public SliceResponse<MemberRegionResponse> toSliceResponse(List<MemberRegionInfo> infos) {
        return new SliceResponse<>(infos.stream().map(this::toResponse).toList(), false);
    }
}
