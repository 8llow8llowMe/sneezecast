package com.sneezecast.domainlayer.advisory.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;

import com.sneezecast.SurveillanceH2TestSupport;
import com.sneezecast.domainlayer.advisory.adapter.out.persistence.entity.AdvisoryEntity;
import com.sneezecast.domainlayer.advisory.adapter.out.persistence.repository.AdvisoryRepository;
import com.sneezecast.domainlayer.advisory.application.exception.AdvisoryErrorCode;
import com.sneezecast.domainlayer.advisory.application.exception.AdvisoryException;
import com.sneezecast.domainlayer.advisory.application.mapper.AdvisoryMapper;
import com.sneezecast.domainlayer.advisory.domain.enums.AdvisoryStatus;
import com.sneezecast.domainlayer.advisory.domain.model.Advisory;
import com.sneezecast.domainlayer.advisory.domain.model.AdvisoryCitation;
import com.sneezecast.domainlayer.advisory.domain.model.AdvisoryHistory;
import com.sneezecast.domainlayer.aggregate.domain.enums.AggregateLevel;
import com.sneezecast.domainlayer.report.domain.model.ReportWeek;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 안내 · 이력 저장 어댑터를 실제 스키마(H2 MySQL 모드)와 트랜잭션으로 돌려 본다. 테스트 메서드에는 트랜잭션을 걸지 않는다 — 낙관적 락 충돌은 두
 * 트랜잭션이 각자 커밋해야 드러난다.
 */
class AdvisoryRepositoryAdapterTest extends SurveillanceH2TestSupport {

    private static final String GARAK_1 = "11240660";
    private static final ReportWeek W40 = ReportWeek.parse("2026-W40");
    private static final AdvisoryCitation CITATION = new AdvisoryCitation(150, 30, 20, 15, AggregateLevel.HIGH, "2026-10-08");
    private static final LocalDateTime PUBLISHED_AT = LocalDateTime.of(2026, 10, 6, 10, 0);

    @Autowired
    private AdvisoryRepositoryAdapter adapter;

    @Autowired
    private AdvisoryHistoryRepositoryAdapter historyAdapter;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate tx;
    private TransactionTemplate newTx;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM advisory");
        jdbcTemplate.update("DELETE FROM advisory_history");
        tx = new TransactionTemplate(transactionManager);
        newTx = new TransactionTemplate(transactionManager);
        newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Test
    @DisplayName("새 안내를 저장하면 버전 0 · 감사 시각이 채워지고, 인용 수치 · 상태가 문자열 컬럼으로 그대로 다시 읽힌다")
    void insertAndFind() {
        Advisory saved = adapter.insert(draft(1L));

        assertThat(saved.version()).isZero();
        assertThat(saved.createdAt()).isNotNull();
        assertThat(adapter.findById(1L)).get().usingRecursiveComparison().ignoringFields("createdAt", "updatedAt").isEqualTo(draft(1L));
        assertThat(jdbcTemplate.queryForMap("SELECT status, cited_level, iso_week FROM advisory WHERE id = 1"))
            .containsEntry("STATUS", "DRAFT")
            .containsEntry("CITED_LEVEL", "HIGH")
            .containsEntry("ISO_WEEK", "2026-W40");
        assertThat(adapter.findById(2L)).isEmpty();
    }

    @Test
    @DisplayName("수정은 상태 · 제목 · 본문 · 발행 시각을 고치고 버전을 하나 올린다. 인용 수치 · 근거 집계는 고치지 않는다")
    void updateBumpsVersion() {
        adapter.insert(draft(1L));

        Advisory published = tx.execute(status -> adapter.update(adapter.findById(1L).orElseThrow().approve().publish(PUBLISHED_AT)));

        assertThat(published.version()).isEqualTo(1L);
        Advisory found = adapter.findById(1L).orElseThrow();
        assertThat(found.status()).isEqualTo(AdvisoryStatus.PUBLISHED);
        assertThat(found.publishedAt()).isEqualTo(PUBLISHED_AT);
        assertThat(found.version()).isEqualTo(1L);
        assertThat(found.citation()).isEqualTo(CITATION);
        assertThat(found.aggregateId()).isEqualTo(7L);
    }

    @Test
    @DisplayName("읽은 버전이 지금 버전과 다르면 고치지 않고 ADVISORY_006 이다")
    void updateWithStaleVersionConflicts() {
        adapter.insert(draft(1L));
        tx.executeWithoutResult(status -> adapter.update(adapter.findById(1L).orElseThrow().edit("먼저 고친 제목", "먼저 고친 본문")));
        Advisory stale = draft(1L).edit("늦게 고친 제목", "늦게 고친 본문");

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> adapter.update(stale)))
            .isInstanceOfSatisfying(AdvisoryException.class, e -> assertThat(e.getErrorCode()).isEqualTo(AdvisoryErrorCode.CONCURRENT_MODIFICATION));
        assertThat(adapter.findById(1L).orElseThrow().title()).isEqualTo("먼저 고친 제목");
    }

    @Test
    @DisplayName("같은 버전을 읽은 두 트랜잭션 중 늦게 flush 한 쪽은 @Version 충돌로 ADVISORY_006 이 되고, 먼저 커밋한 수정이 남는다")
    void concurrentUpdateLosesWithOptimisticLock() {
        adapter.insert(draft(1L));

        tx.executeWithoutResult(status -> {
            Advisory readByA = adapter.findById(1L).orElseThrow();
            // B 가 같은 버전(0)을 읽어 먼저 고치고 커밋한다.
            newTx.executeWithoutResult(inner -> adapter.update(adapter.findById(1L).orElseThrow().edit("B 가 고친 제목", "B 가 고친 본문")));

            assertThatThrownBy(() -> adapter.update(readByA.edit("A 가 고친 제목", "A 가 고친 본문")))
                .isInstanceOfSatisfying(AdvisoryException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(AdvisoryErrorCode.CONCURRENT_MODIFICATION);
                    // 버전 비교가 아니라 flush 의 @Version 조건(where version = 0)에서 졌다.
                    assertThat(e.getCause()).isInstanceOf(OptimisticLockingFailureException.class);
                });
            status.setRollbackOnly();
        });

        Advisory found = adapter.findById(1L).orElseThrow();
        assertThat(found.title()).isEqualTo("B 가 고친 제목");
        assertThat(found.version()).isEqualTo(1L);
    }

    @Test
    @DisplayName("수정은 호출자 트랜잭션이 있어야 하고, 없는 안내는 ADVISORY_001 이다")
    void updateRules() {
        adapter.insert(draft(1L));

        assertThatThrownBy(() -> adapter.update(draft(1L).approve())).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> adapter.update(draft(2L).approve())))
            .isInstanceOfSatisfying(AdvisoryException.class, e -> assertThat(e.getErrorCode()).isEqualTo(AdvisoryErrorCode.ADVISORY_NOT_FOUND));
    }

    @Test
    @DisplayName("잠금 읽기는 그 안내와 같은 동네 · 주의 안내만(자신 포함) 돌려주고, 없는 안내면 빈 목록이며, 호출자 트랜잭션이 있어야 한다")
    void lockDistrictWeekOf() {
        adapter.insert(draft(1L));
        adapter.insert(draft(2L));
        adapter.insert(Advisory.newDraft(3L, 8L, GARAK_1, ReportWeek.parse("2026-W41"), CITATION, "다른 주", "본문"));
        adapter.insert(Advisory.newDraft(4L, 9L, "11230510", W40, CITATION, "다른 동네", "본문"));

        List<Advisory> sameDistrictWeek = tx.execute(status -> adapter.lockDistrictWeekOf(2L));
        List<Advisory> unknown = tx.execute(status -> adapter.lockDistrictWeekOf(404L));

        assertThat(sameDistrictWeek).extracting(Advisory::id).containsExactlyInAnyOrder(1L, 2L);
        assertThat(unknown).isEmpty();
        assertThatThrownBy(() -> adapter.lockDistrictWeekOf(1L)).isInstanceOf(IllegalTransactionStateException.class);
    }

    /**
     * 잠금 대기 초과 · 교착 희생을 저장소가 던지는 경우. H2 로 재현하지 않는다 — H2 2.3 은 잠금 대기 초과({@code LOCK_TIMEOUT}) 뒤 그 트랜잭션의 롤백까지
     * "Unable to rollback" 으로 실패해 변환된 예외를 가린다(MySQL 은 대기 초과가 문장만 되돌리고 롤백은 된다). 그래서 저장소를 대역으로 두고 변환만 본다.
     */
    @Test
    @DisplayName("잠금 읽기 · flush 에서 잠금 대기 초과 · 교착이 나면 ADVISORY_006 으로 바꾼다")
    void lockFailuresAreConcurrentModification() {
        AdvisoryRepository repository = mock(AdvisoryRepository.class);
        AdvisoryMapper mapper = mock(AdvisoryMapper.class);
        AdvisoryRepositoryAdapter unproxied = new AdvisoryRepositoryAdapter(repository, mapper);
        given(repository.findDistrictWeekById(1L)).willReturn(Optional.of(new AdvisoryRepository.DistrictWeekView() {
            @Override
            public String getDistrictCode() {
                return GARAK_1;
            }

            @Override
            public String getIsoWeek() {
                return W40.value();
            }
        }));
        given(repository.findAllByDistrictCodeAndIsoWeek(GARAK_1, W40.value())).willThrow(new CannotAcquireLockException("lock wait timeout"));
        given(repository.findById(1L)).willReturn(Optional.of(AdvisoryEntity.builder().id(1L).version(0L).build()));
        willThrow(new DeadlockLoserDataAccessException("deadlock", null)).given(repository).flush();

        assertThatThrownBy(() -> unproxied.lockDistrictWeekOf(1L)).isInstanceOfSatisfying(AdvisoryException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(AdvisoryErrorCode.CONCURRENT_MODIFICATION));
        assertThatThrownBy(() -> unproxied.update(draft(1L).approve())).isInstanceOfSatisfying(AdvisoryException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(AdvisoryErrorCode.CONCURRENT_MODIFICATION));
    }

    @Test
    @DisplayName("공개 조회는 동네 · 주의 발행됨 · 철회됨만, 요약 조회는 동네 코드 IN × 주 IN 의 발행됨만 읽는다. 동네 코드 · 주가 비면 빈 목록이다")
    void publicQueriesReadOnlyPublicStatuses() {
        adapter.insert(draft(1L));
        adapter.insert(draft(2L).approve());
        adapter.insert(draft(3L).approve().publish(PUBLISHED_AT));
        adapter.insert(draft(4L).approve().publish(PUBLISHED_AT).retract(PUBLISHED_AT.plusDays(1)));
        adapter.insert(draft(5L).toBuilder().isoWeek(W40.previous()).build().approve().publish(PUBLISHED_AT));
        adapter.insert(draft(6L).toBuilder().districtCode("11240670").build().approve().publish(PUBLISHED_AT));
        adapter.insert(draft(7L).toBuilder().isoWeek(W40.minusWeeks(2)).build().approve().publish(PUBLISHED_AT));

        assertThat(adapter.findAllPublicByDistrictCodeAndIsoWeek(GARAK_1, W40)).extracting(Advisory::id).containsExactlyInAnyOrder(3L, 4L);
        assertThat(adapter.findAllPublishedByDistrictCodeInAndIsoWeekIn(List.of(GARAK_1, "11240670", "11240680"), List.of(W40))).extracting(Advisory::id)
            .containsExactlyInAnyOrder(3L, 6L);
        assertThat(adapter.findAllPublishedByDistrictCodeInAndIsoWeekIn(List.of(GARAK_1), List.of(W40, W40.previous()))).extracting(Advisory::id)
            .containsExactlyInAnyOrder(3L, 5L);
        assertThat(adapter.findAllPublishedByDistrictCodeInAndIsoWeekIn(List.of(), List.of(W40))).isEmpty();
        assertThat(adapter.findAllPublishedByDistrictCodeInAndIsoWeekIn(List.of(GARAK_1), List.of())).isEmpty();
    }

    @Test
    @DisplayName("검토 후보 조회는 동네 코드 IN × 한 주의 모든 상태를 읽는다 — 다른 주 · 다른 동네는 빠지고, 동네 코드가 비면 빈 목록이다")
    void candidateQueryReadsAllStatusesOfWeek() {
        adapter.insert(draft(1L));
        adapter.insert(draft(2L).approve());
        adapter.insert(draft(3L).approve().publish(PUBLISHED_AT));
        adapter.insert(draft(4L).approve().publish(PUBLISHED_AT).retract(PUBLISHED_AT.plusDays(1)));
        adapter.insert(draft(5L).toBuilder().isoWeek(W40.previous()).build());
        adapter.insert(draft(6L).toBuilder().districtCode("11240670").build());
        adapter.insert(draft(7L).toBuilder().districtCode("11240680").build());

        assertThat(adapter.findAllByDistrictCodeInAndIsoWeek(List.of(GARAK_1, "11240670"), W40)).extracting(Advisory::id)
            .containsExactlyInAnyOrder(1L, 2L, 3L, 4L, 6L);
        assertThat(adapter.findAllByDistrictCodeInAndIsoWeek(List.of(), W40)).isEmpty();
    }

    @Test
    @DisplayName("이력은 운영자 ID 와 함께 쌓이고, 안내별로 오래된 것부터 읽힌다")
    void historyInsertAndOrder() {
        Advisory draft = draft(1L);
        Advisory edited = draft.edit("고친 제목", "고친 본문");
        historyAdapter.insert(AdvisoryHistory.operatorTransition(11L, draft, null, 42L, null));
        historyAdapter.insert(AdvisoryHistory.operatorTransition(12L, edited, AdvisoryStatus.DRAFT, 43L, "오탈자"));
        historyAdapter.insert(AdvisoryHistory.operatorTransition(13L, draft(2L), null, 42L, null));

        List<AdvisoryHistory> histories = historyAdapter.findAllByAdvisoryId(1L);

        assertThat(histories).extracting(AdvisoryHistory::id, AdvisoryHistory::fromStatus, AdvisoryHistory::toStatus, AdvisoryHistory::operatorId)
            .containsExactly(
                tuple(11L, null, AdvisoryStatus.DRAFT, 42L),
                tuple(12L, AdvisoryStatus.DRAFT, AdvisoryStatus.EDITED, 43L));
        assertThat(histories.get(1)).extracting(AdvisoryHistory::title, AdvisoryHistory::body, AdvisoryHistory::memo)
            .containsExactly("고친 제목", "고친 본문", "오탈자");
        assertThat(histories).allSatisfy(history -> assertThat(history.createdAt()).isNotNull());
        assertThat(historyAdapter.findAllByAdvisoryId(3L)).isEmpty();
    }

    private static Advisory draft(long id) {
        return Advisory.newDraft(id, 7L, GARAK_1, W40, CITATION, "가락1동 증상 보고가 많이 늘었어요", "손 씻기와 기침 예절을 지켜 주세요.");
    }
}
