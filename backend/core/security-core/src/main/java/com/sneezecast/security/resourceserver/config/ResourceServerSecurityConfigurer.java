package com.sneezecast.security.resourceserver.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sneezecast.security.common.handler.CustomAccessDeniedHandler;
import com.sneezecast.security.common.handler.DefaultSecurityErrorResponseWriter;
import com.sneezecast.security.common.handler.SecurityErrorResponseWriter;
import com.sneezecast.security.common.resolver.JwtTokenErrorResolver;
import com.sneezecast.security.resourceserver.handler.OAuth2AuthenticationFailureHandler;
import com.sneezecast.security.resourceserver.jwt.JwtResourceServerProperties;
import com.sneezecast.security.resourceserver.jwt.JwtToMemberConverter;
import com.sneezecast.security.resourceserver.resolver.OAuth2ResourceTokenErrorResolver;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

@EnableMethodSecurity(securedEnabled = true)
public class ResourceServerSecurityConfigurer {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtToMemberConverter jwtToMemberConverter,
        AuthenticationEntryPoint authenticationEntryPoint, AccessDeniedHandler accessDeniedHandler) throws Exception {

        http
            // 1. 불필요한 기능 비활성화
            .cors(AbstractHttpConfigurer::disable) // CORS는 Gateway에서 처리
            .csrf(AbstractHttpConfigurer::disable)
            // 1-1. 세션, 폼, 기본 인증 비활성화
            .httpBasic(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .logout(AbstractHttpConfigurer::disable)
            // 2. 모든 요청 허용 (인증은 JWT + @PreAuthorize로 처리)
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
            // 3. OAuth2 Resource Server 설정
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtToMemberConverter))
                .authenticationEntryPoint(authenticationEntryPoint)
                .accessDeniedHandler(accessDeniedHandler)
            );

        return http.build();
    }

    @Bean
    public JwtDecoder jwtDecoder(JwtResourceServerProperties properties) {
        SecretKey secretKey = new SecretKeySpec(properties.accessKey().getBytes(), "HmacSHA512");
        return NimbusJwtDecoder
            .withSecretKey(secretKey)
            .macAlgorithm(MacAlgorithm.HS512)
            .build();
    }

    @Bean
    public JwtToMemberConverter jwtToMemberConverter() {
        return new JwtToMemberConverter();
    }

    @Bean
    public JwtTokenErrorResolver jwtTokenErrorResolver() {
        return new OAuth2ResourceTokenErrorResolver();
    }

    @Bean
    public AuthenticationEntryPoint authenticationEntryPoint(
        SecurityErrorResponseWriter errorResponseWriter, JwtTokenErrorResolver jwtTokenErrorResolver) {
        return new OAuth2AuthenticationFailureHandler(errorResponseWriter, jwtTokenErrorResolver);
    }

    @Bean
    public AccessDeniedHandler accessDeniedHandler(SecurityErrorResponseWriter errorResponseWriter) {
        return new CustomAccessDeniedHandler(errorResponseWriter);
    }

    @Bean
    @ConditionalOnMissingBean(SecurityErrorResponseWriter.class)
    public SecurityErrorResponseWriter defaultSecurityErrorResponseWriter(ObjectMapper objectMapper) {
        return new DefaultSecurityErrorResponseWriter(objectMapper);
    }
}
