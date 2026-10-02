package com.sneezecast.domainlayer.official.domain.enums;

import com.sneezecast.common.dto.metadata.CodeNameDescribable;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum OfficialSource implements CodeNameDescribable {
    KDCA_NOTIFIABLE("질병관리청 전수신고", "공공데이터포털 전수신고 감염병 발생현황 API 에서 받은 자료"),
    KDCA_SENTINEL("질병관리청 표본감시", "감염병포털 표본감시 통계에서 받은 자료");

    private final String displayName;
    private final String description;
}
