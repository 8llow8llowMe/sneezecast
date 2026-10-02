package com.sneezecast.domainlayer.official.domain.enums;

import com.sneezecast.common.dto.metadata.CodeNameDescribable;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum OfficialPeriodType implements CodeNameDescribable {
    WEEK("주", "질병관리청 주차 기준 한 주"),
    YEAR("연", "한 해 전체. 주차는 0");

    private final String displayName;
    private final String description;
}
