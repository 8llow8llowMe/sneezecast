package com.sneezecast.domainlayer.report.domain.enums;

import static com.sneezecast.domainlayer.report.domain.enums.SymptomGroup.ENTERIC;
import static com.sneezecast.domainlayer.report.domain.enums.SymptomGroup.RESPIRATORY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 비트 값은 저장된 보고와의 계약이다 (entity-design §7). 바뀌면 여기서 먼저 깨져야 한다.
 */
class SymptomGroupTest {

    @Test
    @DisplayName("비트 값이 고정돼 있다 — RESPIRATORY 1, ENTERIC 2")
    void bitsAreFixed() {
        assertThat(Arrays.stream(SymptomGroup.values()).map(Enum::name)).containsExactly("RESPIRATORY", "ENTERIC");
        assertThat(RESPIRATORY.getBit()).isEqualTo(1);
        assertThat(ENTERIC.getBit()).isEqualTo(2);
    }

    @Test
    @DisplayName("비트는 서로 겹치지 않는 2의 거듭제곱이고, 전부 켜도 TINYINT 에 들어간다")
    void bitsAreDistinctPowersOfTwoWithinTinyint() {
        int union = 0;
        for (SymptomGroup group : SymptomGroup.values()) {
            assertThat(Integer.bitCount(group.getBit())).as(group.name()).isEqualTo(1);
            assertThat(union & group.getBit()).as(group.name()).isZero();
            union |= group.getBit();
        }
        assertThat(union).isLessThanOrEqualTo(Byte.MAX_VALUE);
    }

    @Test
    @DisplayName("집합 → 마스크: 증상 없음 0, 호흡기 1, 장관 2, 둘 다 3")
    void toMask() {
        assertThat(SymptomGroup.toMask(Set.of())).isEqualTo(SymptomGroup.NO_SYMPTOM_MASK).isZero();
        assertThat(SymptomGroup.toMask(Set.of(RESPIRATORY))).isEqualTo(1);
        assertThat(SymptomGroup.toMask(Set.of(ENTERIC))).isEqualTo(2);
        assertThat(SymptomGroup.toMask(Set.of(ENTERIC, RESPIRATORY))).isEqualTo(3);
        // 중복은 한 번으로 센다.
        assertThat(SymptomGroup.toMask(List.of(RESPIRATORY, RESPIRATORY))).isEqualTo(1);
    }

    @Test
    @DisplayName("마스크 → 집합: 0 은 빈 집합, 3 은 선언 순서(호흡기 → 장관)")
    void fromMask() {
        assertThat(SymptomGroup.fromMask(0)).isEmpty();
        assertThat(SymptomGroup.fromMask(1)).containsExactly(RESPIRATORY);
        assertThat(SymptomGroup.fromMask(2)).containsExactly(ENTERIC);
        assertThat(SymptomGroup.fromMask(3)).containsExactly(RESPIRATORY, ENTERIC);
    }

    @ParameterizedTest
    @ValueSource(ints = {4, 5, 8, 64, 127, -1, -128})
    @DisplayName("정의되지 않은 비트 · 음수 마스크는 거부한다 — 모르는 비트를 버리면 저장된 증상이 조용히 사라진다")
    void unknownBitsAreRejected(int mask) {
        assertThatThrownBy(() -> SymptomGroup.fromMask(mask))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("null 원소는 증상 없음으로 접지 않고 거부한다")
    void nullElementIsRejected() {
        assertThatThrownBy(() -> SymptomGroup.toMask(Arrays.asList(RESPIRATORY, null)))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> SymptomGroup.toMask(null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("되돌린 집합은 읽기 전용이다")
    void fromMaskIsUnmodifiable() {
        Set<SymptomGroup> groups = SymptomGroup.fromMask(1);

        assertThatThrownBy(() -> groups.add(ENTERIC)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("화면 이름과 설명이 있다 (coding-conventions §7)")
    void hasDisplayNameAndDescription() {
        assertThat(RESPIRATORY.toMetadata().name()).isEqualTo("호흡기");
        assertThat(RESPIRATORY.getDescription()).isEqualTo("발열 · 기침 · 인후통");
        assertThat(ENTERIC.toMetadata().name()).isEqualTo("장관");
        assertThat(ENTERIC.getDescription()).isEqualTo("구토 · 설사");
    }
}
