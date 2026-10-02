package com.sneezecast.domainlayer.official.domain.enums;

import com.sneezecast.common.dto.metadata.CodeNameDescribable;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum OfficialRegionLevel implements CodeNameDescribable {
    NATION("전국", "전국 단위 값. 지역 코드는 00"),
    SIDO("시도", "질병관리청 시도 코드 기준 시도 단위 값");

    private final String displayName;
    private final String description;
}
