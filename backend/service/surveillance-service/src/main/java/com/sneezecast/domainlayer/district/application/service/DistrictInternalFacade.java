package com.sneezecast.domainlayer.district.application.service;

import com.sneezecast.domainlayer.district.adapter.in.internal.dto.response.DistrictInternalResponse;
import com.sneezecast.domainlayer.district.application.port.in.DistrictInternalUseCase;
import com.sneezecast.domainlayer.district.application.service.presenter.DistrictPresenter;
import com.sneezecast.domainlayer.district.application.service.processor.DistrictQueryProcessor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 서비스 간 행정동 조회. 공개 단건 조회와 같은 Processor 를 쓰고 응답 DTO 만 다르다. DB 읽기뿐이라 Facade 에 읽기 트랜잭션을 건다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DistrictInternalFacade implements DistrictInternalUseCase {

    private final DistrictQueryProcessor districtQueryProcessor;
    private final DistrictPresenter districtPresenter;

    @Override
    public DistrictInternalResponse getDistrict(String code) {
        return districtPresenter.toInternalResponse(districtQueryProcessor.getByCode(code));
    }
}
