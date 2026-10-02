package com.sneezecast.domainlayer.district.application.info;

import com.sneezecast.domainlayer.district.domain.model.District;
import lombok.Builder;

/**
 * 행정동 한 건. 공개 검색 · 단건 · 내부 검증 응답이 모두 이 값에서 나온다.
 *
 * @param sigungu 시도 · 시군구 표기. 조립 규칙은 {@link District#sigunguLabel()} 에만 있다
 * @param active  현행이면 true, 폐지된 코드면 false
 */
@Builder
public record DistrictInfo(
    String code,
    String name,
    String sigungu,
    boolean active
) {

    public static DistrictInfo from(District district) {
        return DistrictInfo.builder()
            .code(district.code())
            .name(district.name())
            .sigungu(district.sigunguLabel())
            .active(district.isActive())
            .build();
    }
}
