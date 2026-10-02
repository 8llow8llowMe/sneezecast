package com.sneezecast.domainlayer.official.domain.enums;

import com.sneezecast.common.dto.metadata.CodeNameDescribable;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum IngestChannel implements CodeNameDescribable {
    OPEN_API("공개 API", "공공데이터포털 공개 API 로 받음"),
    PORTAL_JSON("포털 화면 데이터", "감염병포털 화면이 쓰는 JSON 으로 받음");

    private final String displayName;
    private final String description;
}
