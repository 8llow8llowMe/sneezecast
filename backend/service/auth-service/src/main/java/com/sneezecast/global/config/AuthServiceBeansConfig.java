package com.sneezecast.global.config;

import com.sneezecast.common.config.JasyptConfigurer;
import com.sneezecast.redis.config.RedisConfigurer;
import com.sneezecast.security.auth.config.AuthSecurityConfigurer;
import com.sneezecast.storage.config.StorageConfigurer;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * core 모듈의 빈 등록을 명시한다. core 패키지는 컴포넌트 스캔 대상이 아니므로 여기서 빠지면 빈이 없다.
 *
 * <p>persistence-core 의 Snowflake · QueryDSL · JPA Auditing 은 첫 엔티티가 들어오는 기능 이슈에서 함께 추가한다.
 */
@Configuration
@Import({
    JasyptConfigurer.class,
    AuthSecurityConfigurer.class,
    RedisConfigurer.class,
    StorageConfigurer.class
})
public class AuthServiceBeansConfig {

}
