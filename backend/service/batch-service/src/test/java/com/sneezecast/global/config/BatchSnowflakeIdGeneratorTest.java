package com.sneezecast.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sneezecast.persistence.properties.SnowflakeProperties;
import com.sneezecast.persistence.util.SnowflakeIdGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * 상주 JVM 과 수동 실행 JVM 이 같은 환경변수로 떠도 worker-id 가 갈리는지 본다 — 같으면 같은 ms 에 같은 PK 가 나와 upsert 가 다른 행을 덮어쓴다.
 *
 * <p>{@link SnowflakeIdGenerator} 비트 배치: [timestamp][datacenter 5비트][worker 5비트][sequence 12비트].
 */
class BatchSnowflakeIdGeneratorTest {

    private static final int SEQUENCE_BITS = 12;
    private static final int WORKER_BITS = 5;
    private static final long FIVE_BIT_MASK = 0x1F;

    private final BatchServiceBeansConfig config = new BatchServiceBeansConfig();

    @Test
    @DisplayName("상주 JVM(spring.batch.job.enabled=false)은 설정한 worker-id(기본 1) 그대로다")
    void residentJvmUsesConfiguredWorkerId() {
        SnowflakeIdGenerator generator = config.snowflakeIdGenerator(new SnowflakeProperties(0, 1), environment("false"));

        long id = generator.generateId();
        assertThat(workerId(id)).isEqualTo(1);
        assertThat(datacenterId(id)).isZero();
    }

    @Test
    @DisplayName("수동 실행 JVM(spring.batch.job.enabled=true)은 worker-id + 1(기본 2)이다")
    void jobRunJvmUsesNextWorkerId() {
        SnowflakeIdGenerator generator = config.snowflakeIdGenerator(new SnowflakeProperties(3, 1), environment("true"));

        long id = generator.generateId();
        assertThat(workerId(id)).isEqualTo(2);
        assertThat(datacenterId(id)).isEqualTo(3);
    }

    @Test
    @DisplayName("수동 실행에서 worker-id + 1 이 31 을 넘으면 기동 실패다")
    void rejectsWorkerIdOverflowOnJobRun() {
        assertThatThrownBy(() -> config.snowflakeIdGenerator(new SnowflakeProperties(0, 31), environment("true")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("manual job run");
        assertThat(workerId(config.snowflakeIdGenerator(new SnowflakeProperties(0, 31), environment("false")).generateId())).isEqualTo(31);
    }

    @Test
    @DisplayName("상주 JVM 도 worker-id 가 0~31 밖이면 기동 실패다")
    void rejectsWorkerIdOutOfRange() {
        assertThatThrownBy(() -> config.snowflakeIdGenerator(new SnowflakeProperties(0, 32), environment("false")))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> config.snowflakeIdGenerator(new SnowflakeProperties(0, -1), environment("false")))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("수동 실행 판정은 부트의 잡 실행 러너와 같이 정확히 true(대소문자 무시)일 때만이다 — 값이 없으면 상주로 본다")
    void jobRunJvmDetection() {
        assertThat(BatchServiceBeansConfig.isJobRunJvm(environment("true"))).isTrue();
        assertThat(BatchServiceBeansConfig.isJobRunJvm(environment("TRUE"))).isTrue();
        assertThat(BatchServiceBeansConfig.isJobRunJvm(environment("false"))).isFalse();
        assertThat(BatchServiceBeansConfig.isJobRunJvm(environment(""))).isFalse();
        assertThat(BatchServiceBeansConfig.isJobRunJvm(new MockEnvironment())).isFalse();
    }

    private static long workerId(long id) {
        return (id >> SEQUENCE_BITS) & FIVE_BIT_MASK;
    }

    private static long datacenterId(long id) {
        return (id >> (SEQUENCE_BITS + WORKER_BITS)) & FIVE_BIT_MASK;
    }

    private static MockEnvironment environment(String jobEnabled) {
        return new MockEnvironment().withProperty(BatchServiceBeansConfig.JOB_RUN_PROPERTY, jobEnabled);
    }
}
