package com.sneezecast.global.config;

import com.sneezecast.common.config.JasyptConfigurer;
import javax.sql.DataSource;
import org.springframework.batch.support.transaction.ResourcelessTransactionManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * core 모듈의 빈 등록을 명시한다. core 패키지는 컴포넌트 스캔 대상이 아니므로 여기서 빠지면 빈이 없다.
 *
 * <p>공개 API 가 없어 security-core 구성을 올리지 않는다. persistence-core 도 쓰지 않는다 — 적재 대상 테이블은 surveillance 가
 * 정본이라 엔티티 · Auditing · Snowflake 가 필요 없고, 쓰기는 JDBC 로 한다.
 */
@Configuration
@Import(JasyptConfigurer.class)
public class BatchServiceBeansConfig {

    /**
     * JobRepository(배치 메타데이터)와 적재 쓰기 구간용 트랜잭션 매니저. 아래 {@code taskletTransactionManager} 를 정의하는 순간 부트의
     * {@code DataSourceTransactionManagerAutoConfiguration} 이 물러나므로 여기서 명시하고, Spring Batch 자동 구성이 단일 주입으로 찾을 수 있게
     * {@code @Primary} 를 둔다.
     */
    @Primary
    @Bean
    public PlatformTransactionManager transactionManager(DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }

    /**
     * tasklet 스텝 전용 무자원 트랜잭션 매니저.
     *
     * <p>TaskletStep 은 {@code execute()} 전체를 스텝 트랜잭션으로 감싼다. 적재 tasklet 은 그 안에서 외부 API 를 수십 번 부르므로 DB 트랜잭션으로
     * 감싸면 원격 응답을 기다리는 내내 커넥션을 쥔다 (architecture-guide §3-1). 무자원 매니저로 스텝을 돌린다.
     *
     * <p><b>주의</b>: 무자원 매니저도 트랜잭션 동기화를 켠다. 그 안에서 트랜잭션 없이 {@code JdbcTemplate} 을 부르면 커넥션이 스레드에 묶여 스텝이
     * 끝날 때까지 반납되지 않는다. 그래서 Facade 의 DB 접근은 읽기든 쓰기든 전부 primary 매니저의 {@code TransactionTemplate} 안에서 한다.
     */
    @Bean
    public PlatformTransactionManager taskletTransactionManager() {
        return new ResourcelessTransactionManager();
    }
}
