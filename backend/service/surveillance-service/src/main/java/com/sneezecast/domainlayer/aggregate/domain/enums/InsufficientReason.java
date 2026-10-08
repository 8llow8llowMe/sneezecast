package com.sneezecast.domainlayer.aggregate.domain.enums;

import com.sneezecast.common.dto.metadata.CodeNameDescribable;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * {@link AggregateLevel#INSUFFICIENT} 의 이유 (entity-design §2-2 · §7). 판정은 이 선언 순서대로 본다 — 앞 이유에 걸리면 뒤는 보지 않는다.
 */
@Getter
@RequiredArgsConstructor
public enum InsufficientReason implements CodeNameDescribable {
    LOW_SAMPLE("참여 부족", "그 주 참여자가 최소 표본(aggregate.min-sample, 기본 100명)보다 적다"),
    UNSTABLE("참여 급변", "다 찬 주(마감 판정)의 참여자 수가 전주 대비 aggregate.unstable-change-percent(기본 50%) 이상 바뀌었다. 전주가 없거나 표본 미달이면 보지 않는다"),
    NO_BASELINE("기준선 없음", "직전 aggregate.baseline-lookback-weeks(기본 8주) 안에 기준선으로 쓸 수 있는 주가 aggregate.baseline-weeks(기본 4주)보다 적다");

    private final String displayName;
    private final String description;
}
