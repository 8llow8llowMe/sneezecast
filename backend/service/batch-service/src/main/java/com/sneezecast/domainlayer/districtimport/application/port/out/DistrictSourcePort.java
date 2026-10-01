package com.sneezecast.domainlayer.districtimport.application.port.out;

import com.sneezecast.domainlayer.districtimport.domain.model.DistrictSnapshot;

public interface DistrictSourcePort {

    /**
     * 기준 연도의 전국 읍면동 스냅샷을 받는다. 외부 호출이라 트랜잭션 밖에서 부른다. 실패하면 {@code DistrictImportException} 이다.
     */
    DistrictSnapshot fetchSnapshot(int year);
}
