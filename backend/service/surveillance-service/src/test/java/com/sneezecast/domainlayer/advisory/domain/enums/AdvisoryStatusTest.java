package com.sneezecast.domainlayer.advisory.domain.enums;

import static com.sneezecast.domainlayer.advisory.domain.enums.AdvisoryStatus.AI_DRAFTED;
import static com.sneezecast.domainlayer.advisory.domain.enums.AdvisoryStatus.APPROVED;
import static com.sneezecast.domainlayer.advisory.domain.enums.AdvisoryStatus.DRAFT;
import static com.sneezecast.domainlayer.advisory.domain.enums.AdvisoryStatus.EDITED;
import static com.sneezecast.domainlayer.advisory.domain.enums.AdvisoryStatus.PUBLISHED;
import static com.sneezecast.domainlayer.advisory.domain.enums.AdvisoryStatus.RETRACTED;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 상태 전이 표(entity-design §2-5)를 6 × 6 칸 전부로 못 박는다 — 표에 있는 칸만 허용이고 나머지는 모두 거부(409)다.
 */
class AdvisoryStatusTest {

    /** §2-5 표 + EDITED → EDITED(다시 수정). 이 표가 바뀌면 문서와 함께 고친다. */
    private static final Map<AdvisoryStatus, Set<AdvisoryStatus>> ALLOWED = Map.of(
        DRAFT, Set.of(AI_DRAFTED, EDITED, APPROVED),
        AI_DRAFTED, Set.of(EDITED, APPROVED),
        EDITED, Set.of(EDITED, APPROVED),
        APPROVED, Set.of(EDITED, PUBLISHED),
        PUBLISHED, Set.of(RETRACTED),
        RETRACTED, Set.of());

    @Test
    @DisplayName("전이 표의 36칸 — 표에 있는 칸만 허용하고 나머지는 거부한다")
    void transitionTableIsExhaustive() {
        for (AdvisoryStatus from : AdvisoryStatus.values()) {
            for (AdvisoryStatus to : AdvisoryStatus.values()) {
                assertThat(from.canTransitionTo(to)).as(from + " -> " + to).isEqualTo(ALLOWED.get(from).contains(to));
            }
        }
    }

    @Test
    @DisplayName("어느 상태에서도 DRAFT 로 돌아가지 않고, RETRACTED 는 끝 상태다")
    void draftIsCreationOnlyAndRetractedIsTerminal() {
        assertThat(Arrays.stream(AdvisoryStatus.values())).noneSatisfy(from -> assertThat(from.canTransitionTo(DRAFT)).isTrue());
        assertThat(Arrays.stream(AdvisoryStatus.values())).noneSatisfy(to -> assertThat(RETRACTED.canTransitionTo(to)).isTrue());
    }

    @Test
    @DisplayName("발행으로 가는 길은 승인뿐이다 — 승인 뒤 수정하면 다시 승인을 거쳐야 발행된다")
    void publishOnlyFromApproved() {
        assertThat(Arrays.stream(AdvisoryStatus.values()).filter(from -> from.canTransitionTo(PUBLISHED))).containsExactly(APPROVED);
        assertThat(APPROVED.canTransitionTo(EDITED)).isTrue();
        assertThat(EDITED.canTransitionTo(PUBLISHED)).isFalse();
    }

    @Test
    @DisplayName("이름은 §7 값 순서이고 VARCHAR(20) 에 들어가며 라벨 · 설명이 있다")
    void namesMatchDesign() {
        assertThat(Arrays.stream(AdvisoryStatus.values()).map(Enum::name))
            .containsExactly("DRAFT", "AI_DRAFTED", "EDITED", "APPROVED", "PUBLISHED", "RETRACTED");
        assertThat(Arrays.stream(AdvisoryStatus.values())).allSatisfy(status -> {
            assertThat(status.name().length()).isLessThanOrEqualTo(20);
            assertThat(status.toMetadata().name()).isNotBlank();
            assertThat(status.getDescription()).isNotBlank();
        });
    }
}
