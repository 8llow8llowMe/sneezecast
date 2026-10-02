package com.sneezecast.domainlayer.district.application.port.in;

import com.sneezecast.domainlayer.district.adapter.in.internal.dto.response.DistrictInternalResponse;

public interface DistrictInternalUseCase {

    /**
     * auth 가 내 동네를 저장하기 전에 부르는 코드 검증. 폐지된 코드도 {@code active=false} 로 주고, 저장 거부 판단은 호출자가 한다.
     * 없는 코드면 {@code DISTRICT_001}(404).
     */
    DistrictInternalResponse getDistrict(String code);
}
