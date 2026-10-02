package com.sneezecast.domainlayer.district.application.service.presenter;

import com.sneezecast.domainlayer.district.adapter.in.internal.dto.response.DistrictInternalResponse;
import com.sneezecast.domainlayer.district.adapter.in.web.dto.item.DistrictSearchItem;
import com.sneezecast.domainlayer.district.adapter.in.web.dto.response.DistrictDetailResponse;
import com.sneezecast.domainlayer.district.application.info.DistrictInfo;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 행정동 Info → 응답 DTO. 값을 계산하지 않고 옮기기만 한다 — {@code sigungu} 조립은 도메인 모델({@code District#sigunguLabel})이 끝냈다.
 */
@Component
public class DistrictPresenter {

    /** 검색 응답에는 {@code active} 를 싣지 않는다 — 검색은 현행 행정동만 돌려준다. */
    public List<DistrictSearchItem> toSearchItems(List<DistrictInfo> districts) {
        return districts.stream()
            .map(district -> DistrictSearchItem.builder()
                .code(district.code())
                .name(district.name())
                .sigungu(district.sigungu())
                .build())
            .toList();
    }

    public DistrictDetailResponse toDetailResponse(DistrictInfo district) {
        return DistrictDetailResponse.builder()
            .code(district.code())
            .name(district.name())
            .sigungu(district.sigungu())
            .active(district.active())
            .build();
    }

    public DistrictInternalResponse toInternalResponse(DistrictInfo district) {
        return DistrictInternalResponse.builder()
            .code(district.code())
            .name(district.name())
            .sigungu(district.sigungu())
            .active(district.active())
            .build();
    }
}
