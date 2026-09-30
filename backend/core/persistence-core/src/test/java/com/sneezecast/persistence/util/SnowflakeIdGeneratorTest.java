package com.sneezecast.persistence.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SnowflakeIdGeneratorTest {

    @Test
    void 연속_생성한_ID는_중복_없이_단조_증가한다() {
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(1, 1);
        Set<Long> seen = new HashSet<>();
        long previous = Long.MIN_VALUE;

        for (int i = 0; i < 10_000; i++) {
            long id = generator.generateId();
            assertThat(id).isGreaterThan(previous);
            assertThat(seen.add(id)).isTrue();
            previous = id;
        }
    }

    @Test
    void 범위를_벗어난_노드_ID는_거부한다() {
        assertThatThrownBy(() -> new SnowflakeIdGenerator(32, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SnowflakeIdGenerator(0, -1)).isInstanceOf(IllegalArgumentException.class);
    }
}
