package com.sneezecast.domainlayer.auth.adapter.in.web.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class DeviceLabelResolverTest {

    private final DeviceLabelResolver resolver = new DeviceLabelResolver();

    @ParameterizedTest(name = "{1}")
    @CsvSource(delimiter = '|', value = {
        // 시안 Settings-devices 예 세 가지
        "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1"
            + "|iPhone · Safari",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36|Mac · Chrome",
        "Mozilla/5.0 (Linux; Android 14; SM-S918N) AppleWebKit/537.36 (KHTML, like Gecko) SamsungBrowser/26.0 Chrome/122.0.0.0 Mobile Safari/537.36"
            + "|Galaxy · 삼성 인터넷",
        // 파생 · 인앱 브라우저는 Chrome · Safari 토큰을 함께 싣지만 고유 토큰이 이긴다
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36 Edg/129.0.0.0|Windows · Edge",
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Mobile Safari/537.36 KAKAOTALK 10.8.0"
            + "|Android · 카카오톡",
        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148 NAVER(inapp; search; 2000)"
            + "|iPhone · 네이버",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Whale/3.28.266.14 Safari/537.36"
            + "|Windows · Whale",
        "Mozilla/5.0 (iPad; CPU OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) CriOS/129.0 Mobile/15E148 Safari/604.1|iPad · Chrome",
        "Mozilla/5.0 (X11; Linux x86_64; rv:130.0) Gecko/20100101 Firefox/130.0|Linux · Firefox"
    })
    @DisplayName("흔한 User-Agent 를 OS · 브라우저로 줄인다")
    void resolvesKnownAgents(String userAgent, String expected) {
        assertThat(resolver.resolve(userAgent)).isEqualTo(expected);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "curl/8.4.0", "PostmanRuntime/7.39.0"})
    @DisplayName("없거나 모르는 User-Agent 는 '알 수 없는 기기' 다")
    void unknownAgents(String userAgent) {
        assertThat(resolver.resolve(userAgent)).isEqualTo("알 수 없는 기기");
    }

    @Test
    @DisplayName("OS 만 알면 OS 만 쓴다")
    void osOnly() {
        assertThat(resolver.resolve("SomeApp/1.0 (Windows NT 10.0)")).isEqualTo("Windows");
    }

    @Test
    @DisplayName("결과는 50자를 넘지 않는다")
    void labelIsBounded() {
        assertThat(resolver.resolve("Mozilla/5.0 (Linux; Android 14; SM-S918N) SamsungBrowser/26.0")).hasSizeLessThanOrEqualTo(DeviceLabelResolver.MAX_LENGTH);
        assertThat(resolver.resolve("x".repeat(500) + " Windows " + "y".repeat(500))).hasSizeLessThanOrEqualTo(DeviceLabelResolver.MAX_LENGTH);
    }
}
