package com.sneezecast.global.config;

import com.sneezecast.common.config.JasyptConfigurer;
import com.sneezecast.persistence.config.JpaAuditConfig;
import com.sneezecast.security.resourceserver.config.ResourceServerSecurityConfigurer;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * core 모듈의 빈 등록을 명시한다. core 패키지는 컴포넌트 스캔 대상이 아니므로 여기서 빠지면 빈이 없다.
 *
 * <p>보안은 security-core 의 {@code resourceserver} 구성이다 — 토큰을 발급하지 않고 auth 가 발급한 access token 을 검증만 한다.
 * {@code @EnableMethodSecurity} 도 이 구성이 켠다. auth 쪽 {@code AuthSecurityConfigurer} 를 함께 올리면 필터 체인이 둘이 된다.
 *
 * <p>JPA Auditing 은 {@code BaseEntity} 의 생성 · 수정 시각 컬럼용이다. persistence-core 의 Snowflake · QueryDSL 은 생성 ID 엔티티
 * (district 는 batch 가 id 를 할당한다)와 동적 조회가 들어오는 기능 이슈에서 함께 추가한다.
 */
@Configuration
@Import({
    JasyptConfigurer.class,
    ResourceServerSecurityConfigurer.class,
    JpaAuditConfig.class
})
public class SurveillanceServiceBeansConfig {

}
