package com.sneezecast.domainlayer.sentinelimport.application.port.out;

import com.sneezecast.domainlayer.sentinelimport.application.model.SentinelCallBudget;
import com.sneezecast.domainlayer.sentinelimport.domain.model.SentinelFetch;
import com.sneezecast.domainlayer.sentinelimport.domain.model.SentinelIliRow;
import com.sneezecast.domainlayer.sentinelimport.domain.model.SentinelPathogenRow;
import com.sneezecast.domainlayer.sentinelimport.domain.model.SentinelRequest;

/**
 * 표본감시 원천(감염병포털 화면 데이터) 조회. 프로그램에 따라 응답 모양이 달라 메서드를 나눈다 (data-api-analysis §3-3).
 *
 * <p>요청 하나에 화면 + 데이터 두 번을 부르고, 호출 직전마다 {@link SentinelCallBudget} 을 쓴다. 호출 사이 간격은 어댑터가 지킨다.
 */
public interface SentinelSourcePort {

    /** 급성호흡기 · 장관감염증 — 주 × 병원체. 인플루엔자 요청을 주면 {@link IllegalArgumentException} 이다. */
    SentinelFetch<SentinelPathogenRow> fetchPathogens(SentinelRequest request, SentinelCallBudget budget);

    /** 인플루엔자 — 절기의 주 × 연령대. 다른 프로그램 요청을 주면 {@link IllegalArgumentException} 이다. */
    SentinelFetch<SentinelIliRow> fetchInfluenza(SentinelRequest request, SentinelCallBudget budget);
}
