package com.sneezecast.domainlayer.official.domain.enums;

import com.sneezecast.common.dto.metadata.CodeNameDescribable;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum OfficialMetric implements CodeNameDescribable {
    CASE_COUNT("발생 수", "신고된 발생 건수"),
    INCIDENCE_PER_100K("10만 명당 발생률", "인구 10만 명당 발생 건수"),
    ILI_PER_1000("외래 1,000명당 의사환자 분율", "외래 환자 1,000명당 인플루엔자 의사환자 수");

    private final String displayName;
    private final String description;
}
