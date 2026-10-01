package com.sneezecast.global.properties;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EmailSendLimitPropertiesTest {

    @Test
    @DisplayName("0 이하 한도 · 수명은 기본값으로 바꾸지 않고 기동에서 실패한다 — 메시지에 설정 키가 들어간다")
    void nonPositiveValuesFailStartup() {
        assertThatThrownBy(() -> limits(0, Duration.ofMinutes(5)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("auth.email-send.ip-max-send-count");
        assertThatThrownBy(() -> limits(10, Duration.ZERO))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("auth.email-send.code-ttl");
        assertThatThrownBy(() -> limits(10, null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("auth.email-send.code-ttl");
    }

    private static EmailSendLimitProperties limits(int ipMaxSendCount, Duration codeTtl) {
        return new EmailSendLimitProperties(ipMaxSendCount, Duration.ofHours(1), Duration.ofSeconds(60), codeTtl, Duration.ofMinutes(30), 5, 30,
            Duration.ofHours(1));
    }
}
