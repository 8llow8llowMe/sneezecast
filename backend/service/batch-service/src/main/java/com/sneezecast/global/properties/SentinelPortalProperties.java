package com.sneezecast.global.properties;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 감염병포털 표본감시 화면 데이터 설정 (data-api-analysis §3).
 *
 * <p>공개 API 가 아니라 포털 화면이 쓰는 요청이다. 인증키가 없어 비밀값도 없다 — 화면을 한 번 열어 받은 세션 쿠키로 데이터를 받는다.
 *
 * <p><b>요청 간격.</b> 짧게 연달아 보내면 연결이 끊긴다 (약 10여 건 뒤, §3-5). 원천에 부담을 주지 않으려고 최소 3초를 설정으로도 낮추지 못하게
 * 막는다 — 값을 줄이고 싶으면 이 상수를 바꿔야 한다.
 *
 * @param baseUrl         화면 · 데이터 요청의 공통 앞부분
 * @param connectTimeout  연결 timeout
 * @param readTimeout     응답 timeout
 * @param requestInterval HTTP 호출 사이 최소 간격 ({@value #MIN_REQUEST_INTERVAL_SECONDS}초 이상)
 * @param userAgent       {@code User-Agent}. 누가 부르는지 밝힌다 — 포털 운영자가 보고 연락할 수 있게 서비스 주소를 넣는다
 */
@ConfigurationProperties(prefix = "sentinel-portal")
public record SentinelPortalProperties(
    @DefaultValue("https://dportal.kdca.go.kr/pot/is/st") String baseUrl,
    @DefaultValue("3s") Duration connectTimeout,
    @DefaultValue("30s") Duration readTimeout,
    @DefaultValue("3s") Duration requestInterval,
    @DefaultValue(SentinelPortalProperties.DEFAULT_USER_AGENT) String userAgent
) {

    public static final String DEFAULT_USER_AGENT = "Mozilla/5.0 (compatible; sneezecast-batch; +https://www.sneezecast.com)";
    public static final int MIN_REQUEST_INTERVAL_SECONDS = 3;

    public SentinelPortalProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("sentinel-portal.base-url must not be blank");
        }
        requirePositive(connectTimeout, "sentinel-portal.connect-timeout");
        requirePositive(readTimeout, "sentinel-portal.read-timeout");
        if (requestInterval == null || requestInterval.compareTo(Duration.ofSeconds(MIN_REQUEST_INTERVAL_SECONDS)) < 0) {
            throw new IllegalArgumentException("sentinel-portal.request-interval must be at least %ds. value=%s"
                .formatted(MIN_REQUEST_INTERVAL_SECONDS, requestInterval));
        }
        if (userAgent == null || userAgent.isBlank()) {
            throw new IllegalArgumentException("sentinel-portal.user-agent must not be blank");
        }
    }

    private static void requirePositive(Duration value, String key) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(key + " must be positive. value=" + value);
        }
    }
}
