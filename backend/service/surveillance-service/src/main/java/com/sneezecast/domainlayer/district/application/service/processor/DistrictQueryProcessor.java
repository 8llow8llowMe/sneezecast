package com.sneezecast.domainlayer.district.application.service.processor;

import com.sneezecast.domainlayer.district.application.exception.DistrictErrorCode;
import com.sneezecast.domainlayer.district.application.exception.DistrictException;
import com.sneezecast.domainlayer.district.application.info.DistrictInfo;
import com.sneezecast.domainlayer.district.application.port.out.DistrictRepositoryPort;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 행정동 조회. 공개 API(검색 · 단건)와 내부 검증 API 가 같은 로직을 쓰고, 어댑터만 다르다.
 */
@Component
@RequiredArgsConstructor
public class DistrictQueryProcessor {

    /**
     * 검색 결과 최대 건수. 프론트 계약(region-client {@code SEARCH_LIMIT})과 같다 — 자동완성 목록이라 다음 페이지를 두지 않고 상한에서 자른다.
     */
    public static final int SEARCH_LIMIT = 20;

    private final DistrictRepositoryPort districtRepositoryPort;

    /** 현행 행정동만 찾는다 — 폐지된 동은 새로 고를 수 없다. 검색어는 앞뒤 공백을 걷어 낸 비지 않은 값이다. */
    public List<DistrictInfo> search(String keyword) {
        return districtRepositoryPort.searchActive(keyword, SEARCH_LIMIT).stream()
            .map(DistrictInfo::from)
            .toList();
    }

    /**
     * 폐지된 코드도 돌려준다 ({@code active=false}). 이미 고른 동네가 폐지됐는지 화면 · auth 가 알아야 재선택을 안내할 수 있다.
     *
     * @throws DistrictException 코드가 없으면 {@code DISTRICT_NOT_FOUND}(404)
     */
    public DistrictInfo getByCode(String code) {
        return districtRepositoryPort.findByCode(code)
            .map(DistrictInfo::from)
            .orElseThrow(() -> new DistrictException(DistrictErrorCode.DISTRICT_NOT_FOUND));
    }
}
