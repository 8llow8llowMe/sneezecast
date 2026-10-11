package com.sneezecast.domainlayer.advisory.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sneezecast.domainlayer.advisory.domain.enums.ReviewSignal;
import com.sneezecast.domainlayer.advisory.domain.model.ReviewCandidateRule.WeekCounts;
import com.sneezecast.domainlayer.aggregate.domain.enums.AggregateLevel;
import com.sneezecast.domainlayer.aggregate.domain.enums.InsufficientReason;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ReviewCandidateRuleTest {

    private static final ReviewCandidateRule RULE = new ReviewCandidateRule(30, 20);

    @Test
    @DisplayName("참여 급증 — 전주 대비 정확히 30% 증가면 신호, 한 명 모자라면 아니다. 줄어든 주는 아니다")
    void surgeBoundary() {
        assertThat(RULE.signals(normal(130, 0), normal(100, 0))).containsExactly(ReviewSignal.SURGE);
        assertThat(RULE.signals(normal(129, 0), normal(100, 0))).isEmpty();
        // 전주 333 × 30% = 99.9 — 정수 비교라 100명 늘어야 한다(반올림하지 않는다)
        assertThat(RULE.signals(normal(433, 0), normal(333, 0))).containsExactly(ReviewSignal.SURGE);
        assertThat(RULE.signals(normal(432, 0), normal(333, 0))).isEmpty();
        assertThat(RULE.signals(normal(50, 0), normal(100, 0))).isEmpty();
    }

    @Test
    @DisplayName("참여 급증 — 전주 행이 없거나 전주가 참여 부족이면 보지 않는다. 전주가 급변 · 기준선 없음이면 본다")
    void surgeNeedsComparablePreviousWeek() {
        assertThat(RULE.signals(normal(300, 0), null)).isEmpty();
        assertThat(RULE.signals(normal(300, 0), insufficient(99, 0, InsufficientReason.LOW_SAMPLE))).isEmpty();
        assertThat(RULE.signals(normal(300, 0), insufficient(100, 0, InsufficientReason.UNSTABLE))).containsExactly(ReviewSignal.SURGE);
        assertThat(RULE.signals(normal(300, 0), insufficient(100, 0, InsufficientReason.NO_BASELINE))).containsExactly(ReviewSignal.SURGE);
    }

    @Test
    @DisplayName("반복 보고 — 수정된 보고가 참여자의 정확히 20% 면 신호, 한 건 모자라면 아니다")
    void repeatBoundary() {
        assertThat(RULE.signals(normal(150, 30), null)).containsExactly(ReviewSignal.REPEAT);
        assertThat(RULE.signals(normal(150, 29), null)).isEmpty();
        // 참여 101 × 20% = 20.2 — 21건부터다
        assertThat(RULE.signals(normal(101, 21), null)).containsExactly(ReviewSignal.REPEAT);
        assertThat(RULE.signals(normal(101, 20), null)).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = AggregateLevel.class, names = {"SLIGHT", "HIGH"})
    @DisplayName("기준선 대비 변화 — 집계 단계가 SLIGHT · HIGH 면 신호다")
    void baselineFollowsLevel(AggregateLevel level) {
        assertThat(RULE.signals(new WeekCounts(150, 0, level, null), null)).containsExactly(ReviewSignal.BASELINE);
    }

    @Test
    @DisplayName("기준선 대비 변화 — 평소 수준 · 자료 부족은 신호가 아니다")
    void baselineNotForNormalOrInsufficient() {
        assertThat(RULE.signals(normal(150, 0), normal(150, 0))).isEmpty();
        assertThat(RULE.signals(insufficient(150, 0, InsufficientReason.NO_BASELINE), normal(150, 0))).isEmpty();
    }

    @Test
    @DisplayName("참여 부족(LOW_SAMPLE) 칸은 급증 · 반복 조건을 채워도 신호가 없다. 급변 · 기준선 없음 칸은 급증 · 반복을 본다")
    void lowSampleHasNoSignal() {
        assertThat(RULE.signals(insufficient(99, 99, InsufficientReason.LOW_SAMPLE), normal(10, 0))).isEmpty();
        assertThat(RULE.signals(insufficient(300, 60, InsufficientReason.UNSTABLE), normal(100, 0)))
            .containsExactly(ReviewSignal.SURGE, ReviewSignal.REPEAT);
        assertThat(RULE.signals(insufficient(300, 60, InsufficientReason.NO_BASELINE), null)).containsExactly(ReviewSignal.REPEAT);
    }

    @Test
    @DisplayName("신호가 여럿이면 선언 순서(기준선 → 급증 → 반복)다")
    void signalsInDeclarationOrder() {
        assertThat(RULE.signals(new WeekCounts(200, 40, AggregateLevel.HIGH, null), normal(100, 0)))
            .containsExactly(ReviewSignal.BASELINE, ReviewSignal.SURGE, ReviewSignal.REPEAT);
    }

    @Test
    @DisplayName("설정 검사 — 급증 0 이하, 반복 0 이하 · 100 초과는 설정 키를 싣고 실패한다. 반복 100 은 받는다")
    void validatesSettings() {
        assertThatThrownBy(() -> new ReviewCandidateRule(0, 20)).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("advisory.review-candidate.surge-change-percent");
        assertThatThrownBy(() -> new ReviewCandidateRule(30, 0)).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("advisory.review-candidate.repeat-revised-percent");
        assertThatThrownBy(() -> new ReviewCandidateRule(30, 101)).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("advisory.review-candidate.repeat-revised-percent");
        assertThat(new ReviewCandidateRule(30, 100).repeatRevisedPercent()).isEqualTo(100);
    }

    private static WeekCounts normal(int participants, int revised) {
        return new WeekCounts(participants, revised, AggregateLevel.NORMAL, null);
    }

    private static WeekCounts insufficient(int participants, int revised, InsufficientReason reason) {
        return new WeekCounts(participants, revised, AggregateLevel.INSUFFICIENT, reason);
    }
}
