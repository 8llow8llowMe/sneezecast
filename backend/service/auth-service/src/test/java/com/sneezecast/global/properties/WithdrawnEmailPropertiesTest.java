package com.sneezecast.global.properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class WithdrawnEmailPropertiesTest {

    private static final String PEPPER = "withdrawn-email-pepper-0123456789abcdef";

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(PropertiesConfig.class);

    @Test
    @DisplayName("값이 바인딩되고 앞뒤 공백을 지운다")
    void bindsValue() {
        runner.withPropertyValues("member.withdrawn-email.pepper=  " + PEPPER + "  ")
            .run(context -> assertThat(context.getBean(WithdrawnEmailProperties.class).pepper()).isEqualTo(PEPPER));
    }

    @Test
    @DisplayName("없거나 비거나 공백이거나 env 자리표시자가 풀리지 않았으면 기동에서 실패한다 — 메시지에는 키 · 환경변수 이름만 있다")
    void missingPepperFailsStartup() {
        runner.run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("member.withdrawn-email.pepper=${WITHDRAWN_EMAIL_PEPPER}").run(context -> assertThat(context).hasFailed()
            .getFailure().rootCause().hasMessageContaining("member.withdrawn-email.pepper").hasMessageContaining("WITHDRAWN_EMAIL_PEPPER"));

        assertThatThrownBy(() -> new WithdrawnEmailProperties(null)).isInstanceOf(IllegalStateException.class).hasMessageContaining("WITHDRAWN_EMAIL_PEPPER");
        assertThatThrownBy(() -> new WithdrawnEmailProperties("   ")).isInstanceOf(IllegalStateException.class).hasMessageContaining("WITHDRAWN_EMAIL_PEPPER");
    }

    @Test
    @DisplayName("앞뒤 공백을 지운 길이가 32자 미만이면 기동에서 실패하고, 메시지에 값이 실리지 않는다 — 32자부터 받는다")
    void shortPepperFailsStartup() {
        String shortPepper = "a".repeat(WithdrawnEmailProperties.MINIMUM_PEPPER_LENGTH - 1);

        assertThatThrownBy(() -> new WithdrawnEmailProperties(shortPepper)).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("32").message().doesNotContain(shortPepper);
        assertThatThrownBy(() -> new WithdrawnEmailProperties("  " + shortPepper + "  ")).isInstanceOf(IllegalStateException.class);
        assertThat(new WithdrawnEmailProperties("b".repeat(WithdrawnEmailProperties.MINIMUM_PEPPER_LENGTH)).pepper()).hasSize(32);
    }

    @Test
    @DisplayName("toString 은 pepper 를 가린다")
    void toStringMasksPepper() {
        assertThat(new WithdrawnEmailProperties(PEPPER).toString()).doesNotContain(PEPPER).contains("pepper=****");
    }

    @EnableConfigurationProperties(WithdrawnEmailProperties.class)
    static class PropertiesConfig {
    }
}
