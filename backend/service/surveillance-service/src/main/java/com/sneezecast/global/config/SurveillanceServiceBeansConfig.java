package com.sneezecast.global.config;

import com.sneezecast.common.config.JasyptConfigurer;
import com.sneezecast.security.resourceserver.config.ResourceServerSecurityConfigurer;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * core 모듈의 빈 등록을 명시한다. core 패키지는 컴포넌트 스캔 대상이 아니므로 여기서 빠지면 빈이 없다.
 *
 * <p>보안은 security-core 의 {@code resourceserver} 구성이다 — 토큰을 발급하지 않고 auth 가 발급한 access token 을 검증만 한다.
 * {@code @EnableMethodSecurity} 도 이 구성이 켠다. auth 쪽 {@code AuthSecurityConfigurer} 를 함께 올리면 필터 체인이 둘이 된다.
 *
 * <p>persistence-core 의 Snowflake · QueryDSL · JPA Auditing 은 첫 엔티티가 들어오는 기능 이슈에서 함께 추가한다.
 */
@Configuration
@Import({
    JasyptConfigurer.class,
    ResourceServerSecurityConfigurer.class
})
public class SurveillanceServiceBeansConfig {

}
