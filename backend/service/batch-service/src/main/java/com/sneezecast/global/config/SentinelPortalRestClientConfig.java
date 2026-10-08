package com.sneezecast.global.config;

import com.sneezecast.global.properties.SentinelPortalProperties;
import java.net.http.HttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * 감염병포털 표본감시 화면 데이터 호출용 {@link RestClient}.
 *
 * <p>주 1회 · 실행당 최대 8회 호출이라 WebFlux · 서킷브레이커를 두지 않는다. 대신 connect / read timeout 을 명시한다
 * (coding-conventions §9 — 모든 외부 호출에 timeout).
 *
 * <p>쿠키는 어댑터가 화면 응답에서 직접 모아 데이터 요청에 싣는다 — {@link java.net.CookieHandler} 를 붙이면 요청 사이에 상태가 남는다.
 */
@Configuration
public class SentinelPortalRestClientConfig {

    @Bean
    public RestClient sentinelPortalRestClient(RestClient.Builder builder, SentinelPortalProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
            // 실호출로 확인한 경로(2026-10-02 · 10-06)는 HTTP/1.1 이다. JDK 기본값(HTTP/2 업그레이드 시도)으로 확인하지 않은 경로를 타지 않게 고정한다.
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(properties.connectTimeout())
            .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());

        return builder
            .baseUrl(properties.baseUrl())
            .defaultHeader(HttpHeaders.USER_AGENT, properties.userAgent())
            .requestFactory(requestFactory)
            .build();
    }
}
