package com.sneezecast.domainlayer.official.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class OfficialNotifiablePropertiesTest {

    @ParameterizedTest(name = "{0}")
    @ValueSource(ints = {0, 2, 52})
    @DisplayName("0 이상 52 이하를 받는다 — 0 은 지금 주까지 보인다")
    void acceptsZeroToFiftyTwo(int lagWeeks) {
        assertThat(new OfficialNotifiableProperties(lagWeeks).displayLagWeeks()).isEqualTo(lagWeeks);
    }

    @ParameterizedTest(name = "{0}")
    @NullSource
    @ValueSource(ints = {-1, 53})
    @DisplayName("빠졌거나 범위 밖이면 기동에서 실패한다")
    void rejectsMissingOrOutOfRange(Integer lagWeeks) {
        assertThatThrownBy(() -> new OfficialNotifiableProperties(lagWeeks))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("official.notifiable.display-lag-weeks");
    }
}
