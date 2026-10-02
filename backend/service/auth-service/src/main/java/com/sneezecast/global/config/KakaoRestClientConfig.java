package com.sneezecast.global.config;

import com.sneezecast.global.properties.KakaoOAuthProperties;
import java.net.http.HttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * 카카오 로그인 호출용 {@link RestClient} (batch 의 SGIS 클라이언트와 같은 방식).
 *
 * <p>로그인 한 번에 두 번(토큰 교환 · 사용자 정보) 부르는 동기 호출이라 Spring {@code RestClient} 로 충분하다. 대신 connect / read timeout 을 명시한다
 * (coding-conventions §9 — 모든 외부 호출에 timeout). 서킷브레이커는 아직 두지 않는다(후속). 기본 주소를 두지 않는다 — 인가 서버(kauth)와 API
 * 서버(kapi)의 호스트가 달라 어댑터가 설정의 전체 주소를 쓴다.
 */
@Configuration
public class KakaoRestClientConfig {

    @Bean
    public RestClient kakaoRestClient(RestClient.Builder builder, KakaoOAuthProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(properties.connectTimeout())
            .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());

        return builder
            .requestFactory(requestFactory)
            .build();
    }
}
