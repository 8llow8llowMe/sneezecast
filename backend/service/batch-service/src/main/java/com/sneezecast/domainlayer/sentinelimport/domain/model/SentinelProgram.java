package com.sneezecast.domainlayer.sentinelimport.domain.model;

import com.sneezecast.domainlayer.official.domain.enums.OfficialProgram;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 감염병포털 표본감시 프로그램 (data-api-analysis §3-3). 원천 화면 이름({@code icdNm})과 적재 테이블의 {@code program} 을 잇는다.
 *
 * <p>{@code icdNm} 은 요청 경로({@code /{icdNm}.do} · {@code /{icdNm}ListAjax.do})에 그대로 들어간다. {@code keyName} 은 적재 이력
 * {@code request_key} 에 쓰는 짧은 이름이라 바꾸면 과거 이력과 이어지지 않는다 (entity-design §3-3).
 */
@Getter
@RequiredArgsConstructor
public enum SentinelProgram {

    /** 급성호흡기감염증 — 병원체별 신고 수. */
    ARI("ari", "ari", OfficialProgram.ARI),
    /** 장관감염증 — 병원체별 신고 수. */
    ENTERIC("gstrnftn", "enteric", OfficialProgram.ENTERIC),
    /** 인플루엔자 — 의사환자 분율(외래 1,000명당), 연령대별. 절기 단위로 받는다. */
    INFLUENZA_ILI("influ", "influenza", OfficialProgram.INFLUENZA_ILI);

    private final String icdNm;
    private final String keyName;
    private final OfficialProgram officialProgram;

    /** 병원체별 신고 수를 주 단위로 받는 프로그램인지. 인플루엔자만 연령대 × 절기라 형식이 다르다. */
    public boolean isPathogenWeekly() {
        return this != INFLUENZA_ILI;
    }
}
