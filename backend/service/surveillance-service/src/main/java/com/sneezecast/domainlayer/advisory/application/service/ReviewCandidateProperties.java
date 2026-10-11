package com.sneezecast.domainlayer.advisory.application.service;

import com.sneezecast.domainlayer.advisory.domain.model.ReviewCandidateRule;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 예방 안내 검토 후보 신호 설정 ({@code advisory.review-candidate.*}, #266). 값은 application.yml 이 정본이다 — 자리표시자를 두지 않고 배포 설정
 * (compose · Vault)에 {@code ADVISORY_REVIEW_CANDIDATE_*} 를 넣지 않는다. 운영자가 보는 후보 기준이 배포마다 조용히 달라지지 않게 한다.
 *
 * <p>검사는 {@link ReviewCandidateRule} 이 한다. 잘못된 값 · 빠진 값(0)이면 바인딩에서 실패해 기동하지 않는다.
 *
 * @see ReviewCandidateRule 각 값의 뜻과 판정
 */
@ConfigurationProperties(prefix = "advisory.review-candidate")
public record ReviewCandidateProperties(
    int surgeChangePercent,
    int repeatRevisedPercent
) {

    public ReviewCandidateProperties {
        new ReviewCandidateRule(surgeChangePercent, repeatRevisedPercent);
    }

    public ReviewCandidateRule toRule() {
        return new ReviewCandidateRule(surgeChangePercent, repeatRevisedPercent);
    }
}
