package com.sneezecast.domainlayer.aggregate.application.service;

import com.sneezecast.domainlayer.aggregate.domain.model.AggregateRule;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 집계 단계 판정 규칙 설정 ({@code aggregate.*}). 값은 application.yml 이 정본이다 — 자리표시자를 두지 않고 배포 설정(compose · Vault)에
 * {@code AGGREGATE_*} 를 넣지 않는다. relaxed binding 으로 환경변수가 yml 값을 덮으면 {@code rule-version} 은 그대로인 채 규칙만 바뀌어, 집계
 * 행의 {@code rule_version} 으로 과거 판정을 설명할 수 없게 된다. 규칙 값과 {@code rule-version} 은 한 커밋에서 함께 바꾼다.
 *
 * <p>검사는 {@link AggregateRule} 이 한다. 잘못된 값 · 빠진 값(0)이면 바인딩에서 실패해 기동하지 않는다 — 틀린 값이 조용히 기본값으로 바뀌면
 * 운영자가 설정이 먹었는지 알 수 없다.
 *
 * @see AggregateRule 각 값의 뜻과 판정 순서
 */
@ConfigurationProperties(prefix = "aggregate")
public record AggregateProperties(
    String ruleVersion,
    int minSample,
    int unstableChangePercent,
    int slightDeltaPp,
    int highDeltaPp,
    int baselineWeeks,
    int baselineLookbackWeeks
) {

    public AggregateProperties {
        new AggregateRule(ruleVersion, minSample, unstableChangePercent, slightDeltaPp, highDeltaPp, baselineWeeks, baselineLookbackWeeks);
    }

    public AggregateRule toRule() {
        return new AggregateRule(ruleVersion, minSample, unstableChangePercent, slightDeltaPp, highDeltaPp, baselineWeeks, baselineLookbackWeeks);
    }
}
