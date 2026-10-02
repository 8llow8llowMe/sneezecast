package com.sneezecast.global.config;

import com.sneezecast.global.properties.KdcaProperties;
import java.net.http.HttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * 공공데이터포털 질병관리청 API 호출용 {@link RestClient}.
 *
 * <p>주 1회 · 실행당 약 74회 호출이라 WebFlux · 서킷브레이커를 두지 않는다. 대신 connect / read timeout 을 명시한다
 * (coding-conventions §9 — 모든 외부 호출에 timeout).
 */
@Configuration
public class KdcaRestClientConfig {

    @Bean
    public RestClient kdcaRestClient(RestClient.Builder builder, KdcaProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(properties.connectTimeout())
            .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());

        return builder
            .baseUrl(properties.baseUrl())
            .requestFactory(requestFactory)
            .build();
    }
}
