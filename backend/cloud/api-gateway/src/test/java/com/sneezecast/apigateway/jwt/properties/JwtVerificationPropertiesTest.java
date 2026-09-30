package com.sneezecast.apigateway.jwt.properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sneezecast.apigateway.config.JwtVerificationPropertiesConfig;
import java.lang.reflect.Constructor;
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
 * 게이트웨이 검증 키 검사와 마스킹. 짧은 키로 뜨면 health 는 UP 인데 토큰 요청이 전부 401 이 된다.
 */
class JwtVerificationPropertiesTest {

    @Test
    @DisplayName("UTF-8 64바이트 키는 통과한다 — 경계값")
    void acceptsSixtyFourByteKey() {
        assertThatCode(() -> new JwtVerificationProperties("k".repeat(64), false)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("63바이트 키는 거부하고, 메시지에는 설정 이름 · env 이름만 싣는다")
    void rejectsSixtyThreeByteKeyWithoutLeakingIt() {
        String shortKey = "short-secret-" + "x".repeat(50);

        assertThatThrownBy(() -> new JwtVerificationProperties(shortKey, false))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("jwt.access-key")
            .hasMessageContaining("JWT_ACCESS_KEY")
            .hasMessageNotContaining(shortKey);
    }

    @Test
    @DisplayName("길이는 글자 수가 아니라 UTF-8 바이트로 잰다 — 한글 21자(63바이트)는 거부, 22자(66바이트)는 통과")
    void measuresKeyLengthInUtf8Bytes() {
        assertThatThrownBy(() -> new JwtVerificationProperties("가".repeat(21), false)).isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> new JwtVerificationProperties("가".repeat(22), false)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "                                                                    "})
    @DisplayName("키가 없거나 공백뿐이면 거부한다 — 공백 68자처럼 길이는 넘어도 마찬가지")
    void rejectsMissingOrBlankKey(String key) {
        assertThatThrownBy(() -> new JwtVerificationProperties(key, false))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("jwt.access-key");
    }

    @Test
    @DisplayName("toString 은 키 값을 가리고(길이도 숨긴다) fail-open 여부는 그대로 보여 준다")
    void toStringMasksKey() {
        String key = "gateway-" + "x".repeat(60);

        assertThat(new JwtVerificationProperties(key, true).toString())
            .doesNotContain(key, "xxxx")
            .isEqualTo("JwtVerificationProperties[accessKey=****, blacklistFailOpen=true]");
    }

    @Test
    @DisplayName("짧은 JWT_ACCESS_KEY 로는 게이트웨이 컨텍스트가 뜨지 않고, 기동 실패 원인 어디에도 키 값이 없다")
    void shortKeyFailsBindingWithoutLeakingIt() {
        String shortKey = "short-gateway-key-" + "z".repeat(30);

        new ApplicationContextRunner()
            .withUserConfiguration(JwtVerificationPropertiesConfig.class)
            .withPropertyValues("jwt.access-key=" + shortKey, "jwt.blacklist-fail-open=false")
            .run(context -> {
                assertThat(context).hasFailed();
                Throwable failure = context.getStartupFailure();
                // BindFailureAnalyzer 는 BindException 의 property 가 있으면 그 값을 기동 실패 보고에 찍는다.
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
    @DisplayName("jwt.access-key 가 없어도 기동에서 실패한다")
    void missingKeyFailsStartup() {
        new ApplicationContextRunner()
            .withUserConfiguration(JwtVerificationPropertiesConfig.class)
            .withPropertyValues("jwt.blacklist-fail-open=true")
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
