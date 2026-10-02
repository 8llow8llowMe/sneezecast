package com.sneezecast.domainlayer.district.adapter.out.persistence.repository.custom;

import com.sneezecast.domainlayer.district.adapter.out.persistence.entity.DistrictEntity;
import java.util.List;

public interface DistrictCustomRepository {

    /**
     * 현행({@code valid_to_year IS NULL}) 행정동 중 동 이름 또는 {@code 시도 + " " + 시군구} 에 검색어가 들어간 것을 코드 오름차순으로 {@code limit} 건까지.
     * 검색어의 LIKE 와일드카드({@code %} · {@code _})는 글자 그대로 찾는다.
     */
    List<DistrictEntity> searchActive(String keyword, int limit);
}
