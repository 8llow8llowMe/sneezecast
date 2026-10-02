package com.sneezecast.apigateway.config;

import java.util.Arrays;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

@Configuration
public class ApiGatewayCorsConfig {

    /**
     * 브라우저 Origin 이 실제로 백엔드에 도달하는 출처만 남긴다.
     *
     * <p>목록은 security-core {@code AuthSecurityConfigurer} 와 같게 맞춘다 — auth-service 로 직결하는 구성에서도
     * 같은 출처가 통과해야 한다. 한쪽만 고치면 경로에 따라 같은 화면의 요청이 CORS 로 갈린다. 두 목록이 같은지는
     * {@code CorsOriginContractTest} 가 security-core 소스를 읽어 대조한다.
     */
    static final List<String> ALLOWED_ORIGIN_PATTERNS = List.of(
        "https://dev.sneezecast.com",   // 개발 웹
        "https://www.sneezecast.com",   // 운영 웹
        "http://localhost:[*]"          // FE 로컬 개발 서버
    );

    @Bean
    public CorsWebFilter corsWebFilter() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(ALLOWED_ORIGIN_PATTERNS);

        config.setAllowedHeaders(List.of("*"));
        config.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);

        return new CorsWebFilter(source);
    }
}
