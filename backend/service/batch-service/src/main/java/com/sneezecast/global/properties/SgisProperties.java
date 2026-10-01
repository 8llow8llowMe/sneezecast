package com.sneezecast.global.properties;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * SGIS 오픈API 설정 (data-api-analysis §1).
 *
 * <p>인증키가 비어 있어도 기동은 성공한다 — 연 1회 수동 실행하는 잡 하나 때문에 상주 프로세스 전체가 뜨지 못하면 안 된다. 키 검사는
 * 잡을 실행할 때 원천 어댑터가 하고, 없으면 {@code SGIS_CREDENTIALS_MISSING} 으로 실패한다.
 *
 * @param baseUrl        API 기본 주소
 * @param consumerKey    서비스 ID ({@code SGIS_CONSUMER_KEY})
 * @param consumerSecret 보안 Key ({@code SGIS_CONSUMER_SECRET})
 * @param connectTimeout 연결 timeout
 * @param readTimeout    응답 timeout. 경계 응답이 시도 하나에 수 MB 라 넉넉히 둔다
 */
@ConfigurationProperties(prefix = "sgis")
public record SgisProperties(
    @DefaultValue("https://sgisapi.mods.go.kr/OpenAPI3") String baseUrl,
    String consumerKey,
    String consumerSecret,
    @DefaultValue("3s") Duration connectTimeout,
    @DefaultValue("30s") Duration readTimeout
) {

    private static final String MASK = "****";

    public boolean hasCredentials() {
        return consumerKey != null && !consumerKey.isBlank() && consumerSecret != null && !consumerSecret.isBlank();
    }

    /** 인증키는 값도 길이도 드러내지 않는다 (coding-conventions §10). */
    @Override
    public String toString() {
        return "SgisProperties[baseUrl=%s, consumerKey=%s, consumerSecret=%s, connectTimeout=%s, readTimeout=%s]"
            .formatted(baseUrl, MASK, MASK, connectTimeout, readTimeout);
    }
}
