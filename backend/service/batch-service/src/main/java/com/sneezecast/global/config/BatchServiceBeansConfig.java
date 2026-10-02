package com.sneezecast.global.config;

import com.sneezecast.common.config.JasyptConfigurer;
import com.sneezecast.persistence.properties.SnowflakeProperties;
import com.sneezecast.persistence.util.SnowflakeIdGenerator;
import javax.sql.DataSource;
import org.springframework.batch.support.transaction.ResourcelessTransactionManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * core 모듈의 빈 등록을 명시한다. core 패키지는 컴포넌트 스캔 대상이 아니므로 여기서 빠지면 빈이 없다.
 *
 * <p>공개 API 가 없어 security-core 구성을 올리지 않는다. persistence-core 는 Snowflake 생성기만 쓴다 — 적재 대상 테이블은 surveillance 가
 * 정본이라 엔티티 · Auditing 이 필요 없고 쓰기는 JDBC 로 하지만, {@code official_surveillance} · {@code official_source_snapshot} 의 PK 는
 * Snowflake 다. JPA · QueryDSL 은 build.gradle 에서 뺐다. 생성기 빈은 persistence-core 의 {@code SnowflakeConfigurer} 대신 여기서 만든다
 * ({@link #snowflakeIdGenerator}).
 */
@Configuration
@Import(JasyptConfigurer.class)
public class BatchServiceBeansConfig {

    /** 기동 시 잡 하나를 돌리고 끝나는 수동 실행 JVM 의 스위치. 상주 프로세스는 application.yml 의 false 그대로다. */
    static final String JOB_RUN_PROPERTY = "spring.batch.job.enabled";

    /** {@code SnowflakeIdGenerator} 의 worker 비트(5비트) 상한. */
    private static final long MAX_WORKER_ID = 31L;

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

    /**
     * batch 전용 Snowflake 생성기. <b>수동 실행 JVM({@code spring.batch.job.enabled=true})은 {@code snowflake.worker-id + 1} 을 쓴다</b>
     * (상주 기본 1 → 수동 2).
     *
     * <p>수동 실행은 상주 프로세스와 같은 컨테이너 · 같은 환경변수에서 두 번째 JVM 으로 뜬다 ({@code docker exec}). 같은 worker-id 면 두 JVM 이 같은
     * ms 에 같은 id 를 만들 수 있고, {@code official_surveillance} upsert 의 {@code ON DUPLICATE KEY UPDATE} 는 PK 충돌도 UPDATE 로 처리하므로
     * <b>자연키가 다른 행을 조용히 덮어쓴다.</b> 배포 파일은 JVM 마다 다른 값을 넘기지 않으므로 여기서 가른다.
     *
     * <p>결과가 0 ~ 31 을 벗어나면 기동 실패다. surveillance · auth 와는 쓰는 테이블이 달라 worker-id 가 겹쳐도 된다.
     */
    @Bean
    public SnowflakeIdGenerator snowflakeIdGenerator(SnowflakeProperties properties, Environment environment) {
        boolean jobRunJvm = isJobRunJvm(environment);
        long workerId = jobRunJvm ? properties.workerId() + 1 : properties.workerId();
        if (workerId < 0 || workerId > MAX_WORKER_ID) {
            throw new IllegalStateException(
                "snowflake.worker-id must be 0..%d%s. snowflake.worker-id=%d".formatted(
                    jobRunJvm ? MAX_WORKER_ID - 1 : MAX_WORKER_ID, jobRunJvm ? " (manual job run uses worker-id + 1)" : "", properties.workerId()));
        }
        return new SnowflakeIdGenerator(properties.datacenterId(), workerId);
    }

    /**
     * 기동 시 잡을 실행하는 JVM 인가. 부트의 잡 실행 러너와 같이 값이 정확히 {@code true}(대소문자 무시)일 때만이다 — 그 JVM 만 적재를 쓴다.
     * 값이 없으면 false 다 (application.yml 이 항상 false 를 둔다).
     */
    static boolean isJobRunJvm(Environment environment) {
        return "true".equalsIgnoreCase(environment.getProperty(JOB_RUN_PROPERTY, "false"));
    }
}
