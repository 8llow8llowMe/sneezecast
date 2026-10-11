package com.sneezecast.persistence.entity;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;

/**
 * persistence-core 에는 실행 애플리케이션이 없어 {@code @DataJpaTest} 가 찾을 설정을 테스트에만 둔다. 이 패키지가 엔티티 · 저장소 스캔 기준이 된다.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
class PersistenceTestApplication {
}
