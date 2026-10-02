package com.sneezecast.domainlayer.official.domain.enums;

import com.sneezecast.common.dto.metadata.CodeNameDescribable;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum OfficialAgeGroup implements CodeNameDescribable {
    ALL("전체", "연령 구분 없는 전체 값. 기본값"),
    AGE_0("0세", "0세"),
    AGE_1_6("1~6세", "1세 이상 6세 이하"),
    AGE_7_12("7~12세", "7세 이상 12세 이하"),
    AGE_13_18("13~18세", "13세 이상 18세 이하"),
    AGE_19_49("19~49세", "19세 이상 49세 이하"),
    AGE_50_64("50~64세", "50세 이상 64세 이하"),
    AGE_65_PLUS("65세 이상", "65세 이상");

    private final String displayName;
    private final String description;
}
