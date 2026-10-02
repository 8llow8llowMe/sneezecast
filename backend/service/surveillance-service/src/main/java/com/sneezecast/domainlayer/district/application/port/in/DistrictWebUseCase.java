package com.sneezecast.domainlayer.district.application.port.in;

import com.sneezecast.domainlayer.district.adapter.in.web.dto.item.DistrictSearchItem;
import com.sneezecast.domainlayer.district.adapter.in.web.dto.response.DistrictDetailResponse;
import java.util.List;

public interface DistrictWebUseCase {

    /** 현행 행정동 검색. {@code query} 는 앞뒤 공백을 걷어 낸 1~20자다 (웹 경계가 검증한다). */
    List<DistrictSearchItem> searchDistricts(String query);

    /** 폐지된 코드도 {@code active=false} 로 준다. 없는 코드면 {@code DISTRICT_001}(404). */
    DistrictDetailResponse getDistrict(String code);
}
