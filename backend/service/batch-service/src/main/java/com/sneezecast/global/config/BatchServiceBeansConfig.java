package com.sneezecast.global.config;

import com.sneezecast.common.config.JasyptConfigurer;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * core 모듈의 빈 등록을 명시한다. core 패키지는 컴포넌트 스캔 대상이 아니므로 여기서 빠지면 빈이 없다.
 *
 * <p>공개 API 가 없어 security-core 구성을 올리지 않는다. persistence-core 도 쓰지 않는다 — 적재 대상 테이블은 surveillance 가
 * 정본이라 엔티티 · Auditing · Snowflake 가 필요 없고, 쓰기는 JDBC 로 한다.
 */
@Configuration
@Import(JasyptConfigurer.class)
public class BatchServiceBeansConfig {

}
