package com.sneezecast.domainlayer.aggregate.domain.enums;

import com.sneezecast.common.dto.metadata.CodeNameDescribable;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 증상군 비율의 전주 대비 변화 (공개 집계 응답 전용 — 저장하지 않는다). 이름 · 라벨은 프론트 증상별 변화({@code GroupTrend} · {@code TREND_LABEL})와
 * 같다. 임계는 단계 판정과 같은 {@code aggregate.slight-delta-pp} · {@code aggregate.high-delta-pp} 다 ({@code AggregateRule#trend}).
 *
 * <p>이번 주와 전주가 모두 수치가 있는 단계(INSUFFICIENT 아님)일 때만 계산한다. 하나라도 자료 부족이면 변화를 내리지 않는다.
 */
@Getter
@RequiredArgsConstructor
public enum GroupTrend implements CodeNameDescribable {
    DOWN("조금 줄었어요", "전주보다 aggregate.slight-delta-pp(기본 3%p) 이상 낮다"),
    FLAT("비슷해요", "전주와의 차이가 aggregate.slight-delta-pp(기본 3%p) 보다 작다"),
    SLIGHT("조금 늘었어요", "전주보다 aggregate.slight-delta-pp(기본 3%p) 이상 높다"),
    HIGH("많이 늘었어요", "전주보다 aggregate.high-delta-pp(기본 8%p) 이상 높다");

    private final String displayName;
    private final String description;
}
