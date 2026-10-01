package com.sneezecast.global.config;

import com.sneezecast.common.config.JasyptConfigurer;
import com.sneezecast.persistence.config.JpaAuditConfig;
import com.sneezecast.persistence.config.QuerydslConfigurer;
import com.sneezecast.persistence.config.SnowflakeConfigurer;
import com.sneezecast.redis.config.RedisConfigurer;
import com.sneezecast.security.auth.config.AuthSecurityConfigurer;
import com.sneezecast.storage.config.StorageConfigurer;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * core 모듈의 빈 등록을 명시한다. core 패키지는 컴포넌트 스캔 대상이 아니므로 여기서 빠지면 빈이 없다.
 *
 * <p>persistence-core 는 세 가지를 켠다 — Snowflake ID 생성기, {@code JPAQueryFactory}, JPA Auditing({@code BaseEntity} 의
 * createdAt · updatedAt). Auditing 이 빠지면 감사 컬럼이 null 로 남아 NOT NULL 제약에서 저장이 실패한다.
 */
@Configuration
@Import({
    JasyptConfigurer.class,
    AuthSecurityConfigurer.class,
    RedisConfigurer.class,
    StorageConfigurer.class,
    SnowflakeConfigurer.class,
    QuerydslConfigurer.class,
    JpaAuditConfig.class
})
public class AuthServiceBeansConfig {

}
