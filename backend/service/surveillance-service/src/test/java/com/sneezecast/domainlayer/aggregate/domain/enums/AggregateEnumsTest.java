package com.sneezecast.domainlayer.aggregate.domain.enums;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 이름은 DB 값(VARCHAR)과 공개 응답의 계약이고, 라벨은 시안(design-guide "상태 단계")과 같아야 한다.
 */
class AggregateEnumsTest {

    @Test
    @DisplayName("단계는 NORMAL / SLIGHT / HIGH / INSUFFICIENT 이고 라벨이 시안과 같다")
    void levelsMatchDesign() {
        assertThat(Arrays.stream(AggregateLevel.values()).map(Enum::name)).containsExactly("NORMAL", "SLIGHT", "HIGH", "INSUFFICIENT");
        assertThat(Arrays.stream(AggregateLevel.values()).map(level -> level.toMetadata().name()))
            .containsExactly("평소 수준", "조금 늘었어요", "많이 늘었어요", "자료 부족");
    }

    @Test
    @DisplayName("자료 부족 이유는 판정 순서대로 LOW_SAMPLE / UNSTABLE / NO_BASELINE 이다")
    void reasonsInJudgementOrder() {
        assertThat(Arrays.stream(InsufficientReason.values()).map(Enum::name)).containsExactly("LOW_SAMPLE", "UNSTABLE", "NO_BASELINE");
        assertThat(Arrays.stream(InsufficientReason.values())).allSatisfy(reason -> assertThat(reason.getDescription()).isNotBlank());
    }

    @Test
    @DisplayName("이름은 VARCHAR(20) 에 들어간다")
    void namesFitColumn() {
        assertThat(Arrays.stream(AggregateLevel.values())).allSatisfy(level -> assertThat(level.name().length()).isLessThanOrEqualTo(20));
        assertThat(Arrays.stream(InsufficientReason.values())).allSatisfy(reason -> assertThat(reason.name().length()).isLessThanOrEqualTo(20));
    }
}
