package com.sneezecast.domainlayer.district.application.port.out;

import com.sneezecast.domainlayer.district.domain.model.District;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 행정동 읽기 포트. <b>저장 메서드를 두지 않는다</b> — 행정동 행의 쓰기 주체는 batch-service 적재 잡뿐이다 (modules.md batch-service).
 */
public interface DistrictRepositoryPort {

    /**
     * 현행 행정동 중 동 이름 또는 시도 · 시군구 표기({@code 시도 시군구})에 검색어가 들어간 것을 코드 오름차순으로 {@code limit} 건까지 준다.
     * 검색어의 {@code %} · {@code _} 는 와일드카드가 아니라 글자 그대로 찾는다.
     *
     * @param keyword 앞뒤 공백을 걷어 낸 비지 않은 검색어
     */
    List<District> searchActive(String keyword, int limit);

    /** 폐지 여부와 무관하게 코드로 찾는다. 폐지 판정은 호출자가 {@link District#isActive()} 로 한다. */
    Optional<District> findByCode(String code);

    /** 여러 코드를 한 번에 찾는다({@code uk_district_code}). 폐지 여부와 무관하고, 없는 코드는 결과에 없으며 순서는 정하지 않는다. 코드가 비면 조회하지 않고 빈 목록이다. */
    List<District> findAllByCodeIn(Collection<String> codes);

    /** 시군구(SGIS 코드 5자리) 안의 현행 행정동을 코드 오름차순으로. 폐지된 동은 넣지 않는다. 없으면 빈 목록이다. */
    List<District> findActiveBySigunguCode(String sigunguCode);
}
