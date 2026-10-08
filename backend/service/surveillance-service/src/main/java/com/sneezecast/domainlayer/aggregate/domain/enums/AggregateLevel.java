package com.sneezecast.domainlayer.aggregate.domain.enums;

import com.sneezecast.common.dto.metadata.CodeNameDescribable;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 행정동 × 주 집계 단계 (entity-design §2-2 · §7). 이름 · 라벨은 시안(frontend design-guide "상태 단계")이 정본이다 — 2026-10-08 결정으로
 * 비율 절대 임계값 단계(GOOD / NORMAL / CAUTION)를 대체했다.
 *
 * <p>판정은 <b>기준선 대비 증상 보고 비율이 몇 %p 늘었는지</b>로 한다 ({@code AggregateRule}). 표본 부족 · 참여 급변 · 기준선 없음이면
 * {@link #INSUFFICIENT} 이고, 그때는 수치 · 상태색으로 위험을 암시하지 않는다.
 */
@Getter
@RequiredArgsConstructor
public enum AggregateLevel implements CodeNameDescribable {
    NORMAL("평소 수준", "증상 보고 비율이 기준선과 비슷하거나 낮다"),
    SLIGHT("조금 늘었어요", "증상 보고 비율이 기준선보다 aggregate.slight-delta-pp(기본 3%p) 이상 높다"),
    HIGH("많이 늘었어요", "증상 보고 비율이 기준선보다 aggregate.high-delta-pp(기본 8%p) 이상 높다"),
    INSUFFICIENT("자료 부족", "표본이 적거나 참여가 급변했거나 기준선이 없어 판단하지 않는다. 수치 · 상태색을 내리지 않는다");

    private final String displayName;
    private final String description;
}
