package com.sneezecast.domainlayer.advisory.application.info;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sneezecast.domainlayer.advisory.application.info.ReviewCandidateListInfo.Candidate;
import com.sneezecast.domainlayer.advisory.application.info.ReviewCandidateListInfo.Measured;
import com.sneezecast.domainlayer.advisory.domain.enums.ReviewSignal;
import com.sneezecast.domainlayer.aggregate.domain.enums.AggregateLevel;
import com.sneezecast.domainlayer.aggregate.domain.enums.InsufficientReason;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 후보 한 칸의 불변식 — 자료 부족이면 수치 없이 이유만 있고 초안을 만들 수 없다(루트 CLAUDE.md "자료 부족이면 수치로 위험을 암시하지 않는다").
 */
class ReviewCandidateListInfoTest {

    private static final Measured MEASURED = new Measured(30, new BigDecimal("20.0"), new BigDecimal("10.0"), new BigDecimal("10.0"), List.of());

    @Test
    @DisplayName("정상 — 수치가 있는 단계는 수치 · 초안 가능, 자료 부족은 이유만 · 초안 불가")
    void acceptsConsistentCandidates() {
        assertThat(candidate(AggregateLevel.HIGH, null, MEASURED, true).measured()).isEqualTo(MEASURED);
        assertThat(candidate(AggregateLevel.INSUFFICIENT, InsufficientReason.UNSTABLE, null, false).measured()).isNull();
    }

    @Test
    @DisplayName("자료 부족인데 수치가 있으면 거부한다")
    void rejectsInsufficientWithNumbers() {
        assertThatThrownBy(() -> candidate(AggregateLevel.INSUFFICIENT, InsufficientReason.UNSTABLE, MEASURED, false))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("자료 부족이 아닌데 수치가 없으면 거부한다")
    void rejectsMeasuredLevelWithoutNumbers() {
        assertThatThrownBy(() -> candidate(AggregateLevel.NORMAL, null, null, false)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("자료 부족인데 초안을 만들 수 있다고 하면 거부한다")
    void rejectsDraftableInsufficient() {
        assertThatThrownBy(() -> candidate(AggregateLevel.INSUFFICIENT, InsufficientReason.NO_BASELINE, null, true))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private static Candidate candidate(AggregateLevel level, InsufficientReason reason, Measured measured, boolean draftable) {
        return Candidate.builder()
            .districtCode("11440660")
            .signals(List.of(ReviewSignal.REPEAT))
            .level(level)
            .insufficientReason(reason)
            .draftable(draftable)
            .participantCount(150)
            .revisedReportCount(30)
            .measured(measured)
            .build();
    }
}
