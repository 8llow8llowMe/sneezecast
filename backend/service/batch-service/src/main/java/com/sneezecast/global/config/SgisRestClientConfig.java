package com.sneezecast.global.config;

import com.sneezecast.global.properties.SgisProperties;
import java.net.http.HttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * SGIS 호출용 {@link RestClient}.
 *
 * <p>연 1회 수동 실행 · 실행당 약 36회 호출이라 WebFlux · 서킷브레이커를 두지 않는다. 대신 connect / read timeout 을 명시한다
 * (coding-conventions §9 — 모든 외부 호출에 timeout).
 */
@Configuration
public class SgisRestClientConfig {

    @Bean
    public RestClient sgisRestClient(RestClient.Builder builder, SgisProperties properties) {
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
