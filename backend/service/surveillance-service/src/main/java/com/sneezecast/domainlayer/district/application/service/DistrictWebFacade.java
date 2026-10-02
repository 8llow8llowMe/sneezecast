package com.sneezecast.domainlayer.district.application.service;

import com.sneezecast.domainlayer.district.adapter.in.web.dto.item.DistrictSearchItem;
import com.sneezecast.domainlayer.district.adapter.in.web.dto.response.DistrictDetailResponse;
import com.sneezecast.domainlayer.district.application.port.in.DistrictWebUseCase;
import com.sneezecast.domainlayer.district.application.service.presenter.DistrictPresenter;
import com.sneezecast.domainlayer.district.application.service.processor.DistrictQueryProcessor;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 행정동 공개 조회. 둘러보기에서도 쓰므로 회원 확인이 없다. DB 읽기뿐이라 Facade 에 읽기 트랜잭션을 건다 (architecture-guide §3-1).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DistrictWebFacade implements DistrictWebUseCase {

    private final DistrictQueryProcessor districtQueryProcessor;
    private final DistrictPresenter districtPresenter;

    @Override
    public List<DistrictSearchItem> searchDistricts(String query) {
        return districtPresenter.toSearchItems(districtQueryProcessor.search(query));
    }

    @Override
    public DistrictDetailResponse getDistrict(String code) {
        return districtPresenter.toDetailResponse(districtQueryProcessor.getByCode(code));
    }
}
