package com.sneezecast.domainlayer.member.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import com.sneezecast.domainlayer.member.adapter.out.persistence.ReportPurgeRequestRepositoryAdapter;
import com.sneezecast.domainlayer.member.adapter.out.persistence.entity.ReportPurgeRequestEntity;
import com.sneezecast.domainlayer.member.adapter.out.persistence.repository.ReportPurgeRequestRepository;
import com.sneezecast.domainlayer.member.application.mapper.ReportPurgeRequestMapperImpl;
import com.sneezecast.domainlayer.member.application.model.ReportPurgeCallResult;
import com.sneezecast.domainlayer.member.application.model.ReportPurgeRunResult;
import com.sneezecast.domainlayer.member.application.port.out.ReportPurgeCommandPort;
import com.sneezecast.domainlayer.member.application.service.processor.ReportPurgeExecutionProcessor;
import com.sneezecast.domainlayer.member.domain.enums.PurgeReason;
import com.sneezecast.global.properties.ReportPurgeProperties;
import com.sneezecast.persistence.config.JpaAuditConfig;
import com.sneezecast.security.auth.config.JwtAuthPropertiesConfig;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 파기 회차를 <b>실제 {@code @Transactional} 프록시</b>와 H2 로 본다 — 파사드(트랜잭션 없음) → Processor(메서드마다 트랜잭션) → 저장소 어댑터
 * ({@code MANDATORY}) → H2. surveillance 호출 포트만 mock 이다. 스케줄러는 올리지 않고 파사드를 직접 부른다.
 *
 * <p>{@code @DataJpaTest} 의 테스트 트랜잭션을 끈다({@code NOT_SUPPORTED}) — 켜 두면 모든 호출이 바깥 트랜잭션 안이라 "원격 호출이 트랜잭션 밖" 을
 * 볼 수 없다. 대신 테스트마다 테이블을 비운다.
 */
@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "jwt.access-key=sneezecast-report-purge-tx-test-access-secret-key-0123456789-0123456789",
    "jwt.refresh-key=sneezecast-report-purge-tx-test-refresh-secret-key-0123456789-0123456789",
    "jwt.access-expiration=PT15M",
    "jwt.refresh-expiration=P14D",
    "auth.report-purge.scheduler-enabled=false",
    "auth.report-purge.initial-delay=PT1M",
    "auth.report-purge.fixed-delay=PT5M",
    "auth.report-purge.completion-margin=PT5M",
    "auth.report-purge.batch-size=50",
    "auth.report-purge.alert-attempt-threshold=12",
    "auth.report-purge.retention=P365D",
    "auth.report-purge.cleanup-cron=0 30 4 * * *"
})
@Import({
    JpaAuditConfig.class, JwtAuthPropertiesConfig.class, ReportPurgeFacade.class, ReportPurgeExecutionProcessor.class,
    ReportPurgeRequestRepositoryAdapter.class, ReportPurgeRequestMapperImpl.class, ReportPurgeFacadeTransactionTest.PropertiesConfig.class
})
class ReportPurgeFacadeTransactionTest {

    private static final long FIRST_CALL_MEMBER = 7350912846153L;
    private static final long SECOND_CALL_MEMBER = 7350912846271L;
    private static final long FAILING_MEMBER = 7350912846389L;

    @Autowired
    private ReportPurgeFacade facade;

    @Autowired
    private ReportPurgeRequestRepository repository;

    @MockitoBean
    private ReportPurgeCommandPort reportPurgeCommandPort;

    @AfterEach
    void cleanUp() {
        repository.deleteAll();
    }

    @Test
    @DisplayName("surveillance 호출은 트랜잭션 밖이고, 결과는 항목마다 커밋된다 — 1차는 첫 성공 시각만, 대기가 지난 2차는 완료, 실패는 사유")
    void callsOutsideTransactionAndCommitsEachRecord() {
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        LocalDateTime secondCallFirstPurgedAt = now.minusMinutes(59);
        repository.save(row(1L, FIRST_CALL_MEMBER).requestedAt(now).build());
        repository.save(row(2L, SECOND_CALL_MEMBER).requestedAt(now.minusHours(1)).firstPurgedAt(secondCallFirstPurgedAt).attemptCount(1).build());
        repository.save(row(3L, FAILING_MEMBER).requestedAt(now.minusMinutes(2)).build());

        Map<Long, Boolean> transactionActiveAtCall = new ConcurrentHashMap<>();
        when(reportPurgeCommandPort.purgeReporter(anyLong())).thenAnswer(invocation -> {
            long memberId = invocation.getArgument(0);
            transactionActiveAtCall.put(memberId, TransactionSynchronizationManager.isActualTransactionActive());
            return memberId == FAILING_MEMBER ? ReportPurgeCallResult.unavailable("status=503") : ReportPurgeCallResult.purged();
        });

        ReportPurgeRunResult result = facade.purgeDue();

        assertThat(result).isEqualTo(new ReportPurgeRunResult(3, 1, 1, 1, false));
        assertThat(transactionActiveAtCall).containsOnlyKeys(FIRST_CALL_MEMBER, SECOND_CALL_MEMBER, FAILING_MEMBER).doesNotContainValue(true);

        ReportPurgeRequestEntity firstCall = repository.findById(1L).orElseThrow();
        assertThat(firstCall.getAttemptCount()).isEqualTo(1);
        assertThat(firstCall.getFirstPurgedAt()).isNotNull();
        assertThat(firstCall.getCompletedAt()).isNull();

        ReportPurgeRequestEntity secondCall = repository.findById(2L).orElseThrow();
        assertThat(secondCall.getAttemptCount()).isEqualTo(2);
        assertThat(secondCall.getFirstPurgedAt()).isEqualTo(secondCallFirstPurgedAt);
        assertThat(secondCall.getCompletedAt()).isNotNull();

        ReportPurgeRequestEntity failing = repository.findById(3L).orElseThrow();
        assertThat(failing.getAttemptCount()).isEqualTo(1);
        assertThat(failing.getLastError()).isEqualTo("UNAVAILABLE status=503");
        assertThat(failing.getFirstPurgedAt()).isNull();
        assertThat(failing.getCompletedAt()).isNull();
    }

    @Test
    @DisplayName("정리는 실제 트랜잭션에서 기한 지난 완료 행만 지운다")
    void cleanUpDeletesOnlyExpiredCompletedRows() {
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        repository.save(row(1L, FIRST_CALL_MEMBER).requestedAt(now.minusDays(400)).completedAt(now.minusDays(399)).build());
        repository.save(row(2L, SECOND_CALL_MEMBER).requestedAt(now.minusDays(2)).completedAt(now.minusDays(1)).build());
        repository.save(row(3L, FAILING_MEMBER).requestedAt(now.minusDays(400)).attemptCount(500).build());

        assertThat(facade.cleanUpCompleted()).isEqualTo(1);
        assertThat(repository.findAll()).extracting(ReportPurgeRequestEntity::getId).containsExactlyInAnyOrder(2L, 3L);
    }

    private static ReportPurgeRequestEntity.ReportPurgeRequestEntityBuilder row(long id, long memberId) {
        return ReportPurgeRequestEntity.builder().id(id).memberId(memberId).reason(PurgeReason.HEALTH_CONSENT_WITHDRAWN);
    }

    @EnableConfigurationProperties(ReportPurgeProperties.class)
    static class PropertiesConfig {
    }
}
