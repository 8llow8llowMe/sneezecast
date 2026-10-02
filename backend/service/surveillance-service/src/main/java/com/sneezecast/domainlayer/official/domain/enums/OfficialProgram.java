package com.sneezecast.domainlayer.official.domain.enums;

import com.sneezecast.common.dto.metadata.CodeNameDescribable;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum OfficialProgram implements CodeNameDescribable {
    NOTIFIABLE("전수신고 감염병", "전국 주별 · 시도 연별 감염병 발생 수"),
    INFLUENZA_ILI("인플루엔자 의사환자", "인플루엔자 의사환자 분율. 연령대별 행으로 온다"),
    ARI("급성호흡기감염증", "급성호흡기감염증 병원체별 신고 수"),
    ENTERIC("장관감염증", "장관감염증 병원체별 신고 수");

    private final String displayName;
    private final String description;
}
