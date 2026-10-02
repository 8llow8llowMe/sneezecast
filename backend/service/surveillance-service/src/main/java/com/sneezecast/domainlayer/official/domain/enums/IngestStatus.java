package com.sneezecast.domainlayer.official.domain.enums;

import com.sneezecast.common.dto.metadata.CodeNameDescribable;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum IngestStatus implements CodeNameDescribable {
    IMPORTED("적재 완료", "원천을 받아 official_surveillance 에 반영함"),
    FAILED("실패", "적재에 실패함. 원천 데이터는 쓰지 않고 기존 값은 그대로 둔다");

    private final String displayName;
    private final String description;
}
