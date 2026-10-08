package com.sneezecast.domainlayer.member.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sneezecast.domainlayer.member.adapter.out.persistence.entity.ReportPurgeRequestEntity;
import com.sneezecast.domainlayer.member.adapter.out.persistence.repository.ReportPurgeRequestRepository;
import com.sneezecast.domainlayer.member.application.mapper.ReportPurgeRequestMapperImpl;
import com.sneezecast.domainlayer.member.domain.enums.PurgeReason;
import com.sneezecast.domainlayer.member.domain.model.ReportPurgeRequest;
import com.sneezecast.persistence.config.JpaAuditConfig;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 파기 스케줄러의 DB 구간(대상 조회 · 결과 기록 · 완료 행 정리)을 H2 에 실제 스키마를 만들어 본다. JPQL 은 컴파일로 검증되지 않는다.
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Import({JpaAuditConfig.class, ReportPurgeRequestRepositoryAdapter.class, ReportPurgeRequestMapperImpl.class})
class ReportPurgeRequestPersistenceTest {

    /** 회차 기준 시각. 2차 호출 기준(now - 대기 20분)은 {@link #SECOND_CALL_DUE_AT}. */
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 8, 12, 0);
    private static final LocalDateTime SECOND_CALL_DUE_AT = NOW.minusMinutes(20);

    @Autowired
    private ReportPurgeRequestRepositoryAdapter adapter;

    @Autowired
    private ReportPurgeRequestRepository repository;

    @Autowired
    private TestEntityManager testEntityManager;

    @Test
    @DisplayName("대상은 미완료 중 1차 전인 것과 대기 시간이 지난(경계 포함) 것이다 — 대기 중인 2차 · 완료 행은 빠진다")
    void findDueSelectsFirstCallsAndSettledSecondCalls() {
        persist(row(1L).requestedAt(NOW.minusMinutes(1)));                                                    // 1차 전 — 바로
        persist(row(2L).requestedAt(NOW.minusMinutes(30)).firstPurgedAt(NOW.minusMinutes(29)));               // 2차 — 대기 지남
        persist(row(3L).requestedAt(SECOND_CALL_DUE_AT).firstPurgedAt(SECOND_CALL_DUE_AT.plusMinutes(1)));    // 2차 — 정확히 경계
        persist(row(4L).requestedAt(NOW.minusMinutes(10)).firstPurgedAt(NOW.minusMinutes(9)));                // 2차 — 대기 중
        persist(row(5L).requestedAt(NOW.minusHours(2)).firstPurgedAt(NOW.minusHours(2)).completedAt(NOW.minusHours(1)));  // 완료
        persist(row(6L).requestedAt(NOW.minusHours(3)).completedAt(NOW.minusHours(2)));                       // 완료 (첫 성공 시각 없음)

        assertThat(adapter.findDue(SECOND_CALL_DUE_AT, 50)).extracting(ReportPurgeRequest::id).containsExactly(2L, 3L, 1L);
    }

    @Test
    @DisplayName("요청 시각 · ID 순으로 상한만큼만 가져온다 — 오래된 요청이 먼저다")
    void findDueOrdersByRequestedAtThenIdAndLimits() {
        persist(row(30L).requestedAt(NOW.minusMinutes(5)));
        persist(row(20L).requestedAt(NOW.minusMinutes(5)));
        persist(row(10L).requestedAt(NOW.minusMinutes(3)));
        persist(row(40L).requestedAt(NOW.minusMinutes(7)));

        assertThat(adapter.findDue(SECOND_CALL_DUE_AT, 3)).extracting(ReportPurgeRequest::id).containsExactly(40L, 20L, 30L);
    }

    @Test
    @DisplayName("첫 성공은 시도 횟수를 올리고 첫 성공 시각을 남긴다 — 완료 시각을 주지 않으면 미완료로 남는다")
    void recordSuccessFirstCall() {
        persist(row(1L).requestedAt(NOW.minusMinutes(1)).attemptCount(2).lastError("UNAVAILABLE status=503"));

        int updated = adapter.recordSuccess(1L, NOW, null);

        assertThat(updated).isEqualTo(1);
        ReportPurgeRequestEntity entity = repository.findById(1L).orElseThrow();
        assertThat(entity.getAttemptCount()).isEqualTo(3);
        assertThat(entity.getFirstPurgedAt()).isEqualTo(NOW);
        assertThat(entity.getCompletedAt()).isNull();
        assertThat(entity.getUpdatedAt()).isEqualTo(NOW);
        assertThat(entity.getLastError()).as("마지막 실패 사유는 이력으로 둔다").isEqualTo("UNAVAILABLE status=503");
    }

    @Test
    @DisplayName("둘째 성공은 첫 성공 시각을 덮어쓰지 않고, 완료 시각을 주면 완료한다")
    void recordSuccessSecondCallCompletes() {
        LocalDateTime firstPurgedAt = NOW.minusMinutes(25);
        persist(row(1L).requestedAt(NOW.minusMinutes(30)).firstPurgedAt(firstPurgedAt).attemptCount(1));

        assertThat(adapter.recordSuccess(1L, NOW, NOW)).isEqualTo(1);

        ReportPurgeRequestEntity entity = repository.findById(1L).orElseThrow();
        assertThat(entity.getAttemptCount()).isEqualTo(2);
        assertThat(entity.getFirstPurgedAt()).isEqualTo(firstPurgedAt);
        assertThat(entity.getCompletedAt()).isEqualTo(NOW);
        assertThat(adapter.existsIncompleteByMemberId(entity.getMemberId())).isFalse();
    }

    @Test
    @DisplayName("이미 완료된 행에는 성공 · 실패 기록이 닿지 않는다 — 동시 실행이 완료 시각 · 시도 횟수를 덮어쓰지 않는다")
    void completedRowIsNotUpdated() {
        LocalDateTime completedAt = NOW.minusMinutes(3);
        persist(row(1L).requestedAt(NOW.minusMinutes(30)).firstPurgedAt(NOW.minusMinutes(29)).completedAt(completedAt).attemptCount(2));

        assertThat(adapter.recordSuccess(1L, NOW, NOW)).isZero();
        assertThat(adapter.recordFailure(1L, "UNAVAILABLE status=503", NOW)).isZero();

        ReportPurgeRequestEntity entity = repository.findById(1L).orElseThrow();
        assertThat(entity.getCompletedAt()).isEqualTo(completedAt);
        assertThat(entity.getAttemptCount()).isEqualTo(2);
        assertThat(entity.getLastError()).isNull();
    }

    @Test
    @DisplayName("실패는 시도 횟수를 올리고 사유를 200자에서 잘라 남긴다 — 첫 성공 · 완료 시각은 그대로다")
    void recordFailureTruncatesLastError() {
        persist(row(1L).requestedAt(NOW.minusMinutes(1)).attemptCount(4));

        assertThat(adapter.recordFailure(1L, "x".repeat(250), NOW)).isEqualTo(1);

        ReportPurgeRequestEntity entity = repository.findById(1L).orElseThrow();
        assertThat(entity.getAttemptCount()).isEqualTo(5);
        assertThat(entity.getLastError()).hasSize(ReportPurgeRequestEntity.LAST_ERROR_MAX_LENGTH);
        assertThat(entity.getFirstPurgedAt()).isNull();
        assertThat(entity.getCompletedAt()).isNull();
        assertThat(entity.getUpdatedAt()).isEqualTo(NOW);
        assertThat(adapter.findById(1L)).hasValueSatisfying(request -> assertThat(request.attemptCount()).isEqualTo(5));
    }

    @Test
    @DisplayName("정리는 기한 전에 완료된 행만 지운다 — 최근 완료 행과 오래된 미완료 행은 남는다")
    void deleteCompletedBeforeKeepsRecentAndIncomplete() {
        LocalDateTime threshold = NOW.minusDays(365);
        persist(row(1L).requestedAt(threshold.minusDays(2)).completedAt(threshold.minusDays(1)));   // 기한 지난 완료 — 지운다
        persist(row(2L).requestedAt(threshold.minusDays(1)).completedAt(threshold));                // 정확히 기한 — 남긴다 (< 비교)
        persist(row(3L).requestedAt(NOW.minusDays(2)).completedAt(NOW.minusDays(1)));               // 최근 완료
        persist(row(4L).requestedAt(threshold.minusDays(30)).attemptCount(9999));                   // 오래된 미완료 — 포기하지 않는다

        assertThat(adapter.deleteCompletedBefore(threshold)).isEqualTo(1);

        assertThat(repository.findAll()).extracting(ReportPurgeRequestEntity::getId).containsExactlyInAnyOrder(2L, 3L, 4L);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("기록 · 정리를 트랜잭션 밖에서 부르면 거부한다")
    void writesRequireTransaction() {
        assertThatThrownBy(() -> adapter.recordSuccess(1L, NOW, null)).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> adapter.recordFailure(1L, "x", NOW)).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> adapter.deleteCompletedBefore(NOW)).isInstanceOf(IllegalTransactionStateException.class);
    }

    private static ReportPurgeRequestEntity.ReportPurgeRequestEntityBuilder row(long id) {
        return ReportPurgeRequestEntity.builder()
            .id(id)
            .memberId(7350912846153L + id)
            .reason(PurgeReason.HEALTH_CONSENT_WITHDRAWN);
    }

    private void persist(ReportPurgeRequestEntity.ReportPurgeRequestEntityBuilder builder) {
        repository.save(builder.build());
        testEntityManager.flush();
        testEntityManager.clear();
    }
}
