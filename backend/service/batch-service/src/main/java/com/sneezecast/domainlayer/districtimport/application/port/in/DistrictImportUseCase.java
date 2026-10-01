package com.sneezecast.domainlayer.districtimport.application.port.in;

import com.sneezecast.domainlayer.districtimport.application.command.DistrictImportCommand;
import com.sneezecast.domainlayer.districtimport.application.model.DistrictImportResult;

public interface DistrictImportUseCase {

    /**
     * 지정한 기준 연도의 SGIS 스냅샷으로 행정동 마스터를 맞춘다. 실패하면 {@code DistrictImportException} 을 던진다.
     */
    DistrictImportResult importDistricts(DistrictImportCommand command);
}
