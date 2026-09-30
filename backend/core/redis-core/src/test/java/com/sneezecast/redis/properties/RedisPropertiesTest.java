package com.sneezecast.redis.properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sneezecast.redis.properties.RedisProperties.SentinelNode;
import com.sneezecast.redis.properties.enums.RedisMode;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class RedisPropertiesTest {

    @Test
    void sentinelNodes_문자열을_노드_목록으로_파싱한다() {
        RedisProperties properties = sentinel(" 192.168.0.11:26379, 192.168.0.13:26380 ,192.168.0.12 ", null);

        assertThat(properties.resolvedSentinels()).containsExactly(
            new SentinelNode("192.168.0.11", 26379),
            new SentinelNode("192.168.0.13", 26380),
            new SentinelNode("192.168.0.12", 26379));
    }

    @Test
    void sentinelNodes_문자열이_yml_목록보다_우선한다() {
        RedisProperties properties = sentinel("192.168.0.11:26379", List.of(new SentinelNode("localhost", 26379)));

        assertThat(properties.resolvedSentinels()).containsExactly(new SentinelNode("192.168.0.11", 26379));
    }

    @Test
    void sentinelNodes_가_없으면_yml_목록을_쓰고_둘_다_없으면_빈_목록이다() {
        assertThat(sentinel(null, List.of(new SentinelNode("localhost", 26379))).resolvedSentinels())
            .containsExactly(new SentinelNode("localhost", 26379));
        assertThat(sentinel("  ", null).resolvedSentinels()).isEmpty();
    }

    @Test
    void 형식이_깨진_노드는_조용히_버리지_않고_예외로_올린다() {
        assertThatThrownBy(() -> sentinel(":26379", null).resolvedSentinels())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("호스트가 없습니다");
        assertThatThrownBy(() -> sentinel("192.168.0.11:port", null).resolvedSentinels())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("포트를 읽을 수 없습니다");
    }

    @Test
    void 키_프리픽스가_비어_있으면_기본값을_쓰고_있으면_trim_한다() {
        assertThat(withKeyPrefix(null).normalizedKeyPrefix()).isEqualTo("sneezecast");
        assertThat(withKeyPrefix(" ").normalizedKeyPrefix()).isEqualTo("sneezecast");
        assertThat(withKeyPrefix(" custom ").normalizedKeyPrefix()).isEqualTo("custom");
    }

    @Test
    void 명령_타임아웃이_없으면_1초를_쓴다() {
        assertThat(withCommandTimeout(null).resolvedCommandTimeout()).isEqualTo(Duration.ofSeconds(1));
        assertThat(withCommandTimeout(Duration.ofMillis(500)).resolvedCommandTimeout()).isEqualTo(Duration.ofMillis(500));
    }

    @Test
    void 명령_타임아웃이_0_이하면_기동에서_실패시킨다() {
        assertThatThrownBy(() -> withCommandTimeout(Duration.ZERO).resolvedCommandTimeout())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("command-timeout");
        assertThatThrownBy(() -> withCommandTimeout(Duration.ofSeconds(-1)).resolvedCommandTimeout())
            .isInstanceOf(IllegalStateException.class);
    }

    private static RedisProperties sentinel(String sentinelNodes, List<SentinelNode> sentinels) {
        return new RedisProperties(RedisMode.SENTINEL, null, null, "mymaster", null, sentinels, sentinelNodes, null, null);
    }

    private static RedisProperties withKeyPrefix(String keyPrefix) {
        return new RedisProperties(RedisMode.STANDALONE, "localhost", 6379, null, null, null, null, keyPrefix, null);
    }

    private static RedisProperties withCommandTimeout(Duration commandTimeout) {
        return new RedisProperties(RedisMode.STANDALONE, "localhost", 6379, null, null, null, null, null, commandTimeout);
    }
}
