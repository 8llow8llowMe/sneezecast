package com.sneezecast.security.auth.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sneezecast.security.auth.config.JwtAuthPropertiesConfig;
import java.lang.reflect.Constructor;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.boot.diagnostics.FailureAnalyzer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * 발급 설정의 서명 키 검사와 마스킹. 키 길이는 서명 · 검증과 같은 UTF-8 바이트로 잰다.
 */
class JwtAuthPropertiesTest {

    private static final String KEY_64_BYTES = "a".repeat(64);
    private static final String KEY_63_BYTES = "b".repeat(63);
    private static final Duration ACCESS = Duration.ofMinutes(15);
    private static final Duration REFRESH = Duration.ofDays(14);

    @Test
    @DisplayName("두 키가 UTF-8 64바이트면 통과한다 — 경계값")
    void acceptsSixtyFourByteKeys() {
        assertThatCode(() -> new JwtAuthProperties(KEY_64_BYTES, ACCESS, KEY_64_BYTES, REFRESH)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("63바이트 키는 access · refresh 모두 거부하고, 메시지에는 설정 이름 · env 이름만 싣는다")
    void rejectsSixtyThreeByteKeysWithoutLeakingThem() {
        assertThatThrownBy(() -> new JwtAuthProperties(KEY_63_BYTES, ACCESS, KEY_64_BYTES, REFRESH))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("jwt.access-key")
            .hasMessageContaining("JWT_ACCESS_KEY")
            .hasMessageNotContaining(KEY_63_BYTES);
        assertThatThrownBy(() -> new JwtAuthProperties(KEY_64_BYTES, ACCESS, KEY_63_BYTES, REFRESH))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("jwt.refresh-key")
            .hasMessageContaining("JWT_REFRESH_KEY")
            .hasMessageNotContaining(KEY_63_BYTES);
    }

    @Test
    @DisplayName("길이는 글자 수가 아니라 UTF-8 바이트로 잰다 — 한글 21자(63바이트)는 거부, 22자(66바이트)는 통과")
    void measuresKeyLengthInUtf8Bytes() {
        assertThatThrownBy(() -> new JwtAuthProperties("가".repeat(21), ACCESS, KEY_64_BYTES, REFRESH))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> new JwtAuthProperties("가".repeat(22), ACCESS, "가".repeat(22), REFRESH)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "                                                                    "})
    @DisplayName("키가 없거나 공백뿐이면 거부한다 — 공백 68자처럼 길이는 넘어도 마찬가지")
    void rejectsMissingOrBlankKeys(String key) {
        assertThatThrownBy(() -> new JwtAuthProperties(key, ACCESS, KEY_64_BYTES, REFRESH))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("jwt.access-key");
        assertThatThrownBy(() -> new JwtAuthProperties(KEY_64_BYTES, ACCESS, key, REFRESH))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("jwt.refresh-key");
    }

    @Test
    @DisplayName("toString 은 키 값을 가리고(길이도 숨긴다) 만료는 그대로 보여 준다")
    void toStringMasksKeys() {
        String accessKey = "access-" + "x".repeat(60);
        String refreshKey = "refresh-" + "y".repeat(60);

        String text = new JwtAuthProperties(accessKey, ACCESS, refreshKey, REFRESH).toString();

        assertThat(text)
            .doesNotContain(accessKey, refreshKey, "xxxx", "yyyy")
            .isEqualTo("JwtAuthProperties[accessKey=****, accessExpiration=PT15M, refreshKey=****, refreshExpiration=PT336H]");
    }

    @Test
    @DisplayName("짧은 키로는 컨텍스트가 뜨지 않고, 기동 실패 원인 어디에도 키 값이 없다")
    void shortKeyFailsBindingWithoutLeakingIt() {
        String shortKey = "short-access-key-" + "z".repeat(30);

        new ApplicationContextRunner()
            .withUserConfiguration(JwtAuthPropertiesConfig.class)
            .withPropertyValues(
                "jwt.access-key=" + shortKey,
                "jwt.access-expiration=15m",
                "jwt.refresh-key=" + KEY_64_BYTES,
                "jwt.refresh-expiration=14d")
            .run(context -> {
                assertThat(context).hasFailed();
                Throwable failure = context.getStartupFailure();
                // BindFailureAnalyzer 는 BindException 의 property 가 있으면 그 값을 기동 실패 보고에 찍는다.
                // record 생성자에서 던지면 property 가 비어 있어 값이 찍히지 않는다.
                assertThat(failure).hasCauseInstanceOf(BindException.class);
                assertThat(((BindException) failure.getCause()).getProperty()).isNull();
                assertThat(failure).rootCause()
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("jwt.access-key");
                for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                    assertThat(String.valueOf(cause.getMessage())).doesNotContain(shortKey);
                }
                // 실제 기동 실패 보고(BindFailureAnalyzer)에도 키가 없어야 한다. Boot 가 값 출력 방식을 바꾸면 여기서 잡힌다.
                assertThat(bindFailureReport(failure)).contains("Failed to bind").doesNotContain(shortKey);
            });
    }

    @Test
    @DisplayName("jwt.* 설정이 통째로 없어도 기동에서 실패한다")
    void missingPropertiesFailStartup() {
        new ApplicationContextRunner()
            .withUserConfiguration(JwtAuthPropertiesConfig.class)
            .run(context -> assertThat(context).hasFailed()
                .getFailure().rootCause().hasMessageContaining("jwt.access-key"));
    }

    // BindFailureAnalyzer 는 package-private 이라 공개 인터페이스(FailureAnalyzer)로 불러 쓴다. 클래스가 사라지면 테스트가 실패해 업그레이드 때 알려 준다.
    private static String bindFailureReport(Throwable failure) throws Exception {
        Class<?> type = Class.forName("org.springframework.boot.diagnostics.analyzer.BindFailureAnalyzer");
        Constructor<?> constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        FailureAnalysis analysis = ((FailureAnalyzer) constructor.newInstance()).analyze(failure);
        assertThat(analysis).as("BindFailureAnalyzer 가 이 기동 실패를 분석해야 한다").isNotNull();
        return analysis.getDescription() + System.lineSeparator() + analysis.getAction();
    }
}
