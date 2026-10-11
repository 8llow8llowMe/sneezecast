package com.sneezecast.domainlayer.advisory.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.sneezecast.SurveillanceH2TestSupport;
import com.sneezecast.domainlayer.advisory.application.command.AdvisoryDraftCommand;
import com.sneezecast.domainlayer.advisory.application.command.AdvisoryEditCommand;
import com.sneezecast.domainlayer.advisory.application.command.AdvisoryRetractCommand;
import com.sneezecast.domainlayer.advisory.application.exception.AdvisoryErrorCode;
import com.sneezecast.domainlayer.advisory.application.exception.AdvisoryException;
import com.sneezecast.domainlayer.advisory.application.info.AdvisoryDetailInfo;
import com.sneezecast.domainlayer.advisory.application.info.AdvisoryHistoryInfo;
import com.sneezecast.domainlayer.advisory.application.info.AdvisoryInfo;
import com.sneezecast.domainlayer.advisory.application.port.in.AdvisoryOperatorUseCase;
import com.sneezecast.domainlayer.advisory.domain.enums.AdvisoryStatus;
import com.sneezecast.domainlayer.advisory.domain.model.AdvisoryCitation;
import com.sneezecast.domainlayer.aggregate.domain.enums.AggregateLevel;
import com.sneezecast.domainlayer.report.domain.model.ReportWeek;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Stream;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 운영자 안내 처리를 유스케이스 포트(트랜잭션 프록시)부터 실제 스키마(H2 MySQL 모드)까지 돌려 본다. 근거 집계 행은 JDBC 로 넣는다 — 마감 · 자료 부족
 * 조합을 재계산 잡 없이 바로 만든다.
 */
class AdvisoryOperatorFacadeTest extends SurveillanceH2TestSupport {

    private static final String GARAK_1 = "11240660";
    private static final ReportWeek W40 = ReportWeek.parse("2026-W40");
    private static final long OPERATOR = 9001L;
    private static final long OTHER_OPERATOR = 9002L;
    private static final LocalDateTime FINALIZED_AT = LocalDateTime.of(2026, 10, 5, 0, 10);
    private static final String TITLE = "가락1동 호흡기 증상 보고가 늘었어요";
    private static final String BODY = "손 씻기와 기침 예절을 지켜 주세요.";

    @Autowired
    private AdvisoryOperatorUseCase useCase;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM advisory");
        jdbcTemplate.update("DELETE FROM advisory_history");
        jdbcTemplate.update("DELETE FROM district_weekly_aggregate");
    }

    @Test
    @DisplayName("마감된 집계로 만든 초안은 DRAFT · 버전 0 이고, 인용 수치를 집계에서 복사하고, 생성 이력에 운영자를 남긴다")
    void createDraftCopiesCitation() {
        insertAggregate(100L, AggregateLevel.SLIGHT, null, true);

        AdvisoryInfo draft = useCase.createDraft(OPERATOR, new AdvisoryDraftCommand(GARAK_1, W40, TITLE, BODY));

        assertThat(draft.status()).isEqualTo(AdvisoryStatus.DRAFT);
        assertThat(draft.version()).isZero();
        assertThat(draft.aggregateId()).isEqualTo(100L);
        assertThat(draft.citation()).isEqualTo(new AdvisoryCitation(150, 30, 20, 15, AggregateLevel.SLIGHT, "2026-10-08"));
        assertThat(useCase.getDetail(draft.id()).histories()).singleElement().satisfies(history -> {
            assertThat(history.fromStatus()).isNull();
            assertThat(history.toStatus()).isEqualTo(AdvisoryStatus.DRAFT);
            assertThat(history.operatorId()).isEqualTo(OPERATOR);
            assertThat(history).extracting(AdvisoryHistoryInfo::title, AdvisoryHistoryInfo::body).containsExactly(TITLE, BODY);
        });
        assertThat(jdbcTemplate.queryForObject("SELECT operator_id FROM advisory_history", Long.class)).isEqualTo(OPERATOR);
    }

    @Test
    @DisplayName("초안 생성 전제 — 집계 행 없음 ADVISORY_002, 마감 전 ADVISORY_003, 자료 부족 ADVISORY_004 이고 아무것도 남지 않는다")
    void createDraftPreconditions() {
        assertErrorCode(() -> useCase.createDraft(OPERATOR, new AdvisoryDraftCommand(GARAK_1, W40, TITLE, BODY)), AdvisoryErrorCode.AGGREGATE_NOT_FOUND);

        insertAggregate(100L, AggregateLevel.HIGH, null, false);
        assertErrorCode(() -> useCase.createDraft(OPERATOR, new AdvisoryDraftCommand(GARAK_1, W40, TITLE, BODY)), AdvisoryErrorCode.AGGREGATE_NOT_FINALIZED);

        jdbcTemplate.update("DELETE FROM district_weekly_aggregate");
        insertAggregate(100L, AggregateLevel.INSUFFICIENT, "LOW_SAMPLE", true);
        assertErrorCode(() -> useCase.createDraft(OPERATOR, new AdvisoryDraftCommand(GARAK_1, W40, TITLE, BODY)), AdvisoryErrorCode.AGGREGATE_INSUFFICIENT);

        assertThat(count("advisory")).isZero();
        assertThat(count("advisory_history")).isZero();
    }

    @Test
    @DisplayName("집계 행이 나중에 바뀌어도 안내의 인용 수치는 초안 생성 때 값 그대로다")
    void citationIsFixedAfterAggregateChanges() {
        insertAggregate(100L, AggregateLevel.SLIGHT, null, true);
        AdvisoryInfo draft = useCase.createDraft(OPERATOR, new AdvisoryDraftCommand(GARAK_1, W40, TITLE, BODY));

        // 마감된 행은 재계산이 고치지 않지만, 어떤 경로로든 집계가 바뀌어도 안내가 따라가지 않는지 본다.
        jdbcTemplate.update("UPDATE district_weekly_aggregate SET participant_count = 400, symptomatic_count = 90, respiratory_count = 60,"
            + " enteric_count = 40, level = 'HIGH', rule_version = '2027-01-01' WHERE id = 100");
        AdvisoryInfo approved = useCase.approve(OPERATOR, draft.id(), draft.version());

        assertThat(approved.citation()).isEqualTo(new AdvisoryCitation(150, 30, 20, 15, AggregateLevel.SLIGHT, "2026-10-08"));
        assertThat(useCase.getDetail(draft.id()).advisory().citation()).isEqualTo(approved.citation());
    }

    @Test
    @DisplayName("초안 → 수정 → 승인 → 발행 → 철회가 버전을 하나씩 올리고, 전이마다 운영자 · 메모와 함께 이력이 한 행씩 쌓인다")
    void fullLifecycleRecordsHistory() {
        insertAggregate(100L, AggregateLevel.HIGH, null, true);
        AdvisoryInfo draft = useCase.createDraft(OPERATOR, new AdvisoryDraftCommand(GARAK_1, W40, TITLE, BODY));

        AdvisoryInfo edited = useCase.edit(OTHER_OPERATOR, draft.id(), new AdvisoryEditCommand(0L, "고친 제목", "고친 본문", "문장 다듬기"));
        AdvisoryInfo approved = useCase.approve(OPERATOR, draft.id(), edited.version());
        AdvisoryInfo published = useCase.publish(OPERATOR, draft.id(), approved.version());
        AdvisoryInfo retracted = useCase.retract(OTHER_OPERATOR, draft.id(), new AdvisoryRetractCommand(published.version(), "집계 오류 확인"));

        assertThat(List.of(edited, approved, published, retracted)).extracting(AdvisoryInfo::status, AdvisoryInfo::version).containsExactly(
            tuple(AdvisoryStatus.EDITED, 1L), tuple(AdvisoryStatus.APPROVED, 2L), tuple(AdvisoryStatus.PUBLISHED, 3L), tuple(AdvisoryStatus.RETRACTED, 4L));
        assertThat(published.publishedAt()).isNotNull();
        assertThat(published.publishedAt().getNano()).as("초 단위 — MySQL TIMESTAMP 와 같다").isZero();
        assertThat(retracted.retractedAt()).isNotNull();
        assertThat(retracted.publishedAt()).isEqualTo(published.publishedAt());

        AdvisoryDetailInfo detail = useCase.getDetail(draft.id());
        assertThat(detail.advisory().status()).isEqualTo(AdvisoryStatus.RETRACTED);
        assertThat(detail.histories())
            .extracting(AdvisoryHistoryInfo::fromStatus, AdvisoryHistoryInfo::toStatus, AdvisoryHistoryInfo::operatorId, AdvisoryHistoryInfo::memo)
            .containsExactly(
                tuple(null, AdvisoryStatus.DRAFT, OPERATOR, null),
                tuple(AdvisoryStatus.DRAFT, AdvisoryStatus.EDITED, OTHER_OPERATOR, "문장 다듬기"),
                tuple(AdvisoryStatus.EDITED, AdvisoryStatus.APPROVED, OPERATOR, null),
                tuple(AdvisoryStatus.APPROVED, AdvisoryStatus.PUBLISHED, OPERATOR, null),
                tuple(AdvisoryStatus.PUBLISHED, AdvisoryStatus.RETRACTED, OTHER_OPERATOR, "집계 오류 확인"));
        // 이력의 제목 · 본문은 그 시점의 값이다 — 승인 · 발행된 본문을 복원할 수 있다.
        assertThat(detail.histories().get(3)).extracting(AdvisoryHistoryInfo::title, AdvisoryHistoryInfo::body).containsExactly("고친 제목", "고친 본문");
        assertThat(detail.histories().get(0)).extracting(AdvisoryHistoryInfo::title, AdvisoryHistoryInfo::body).containsExactly(TITLE, BODY);
    }

    @Test
    @DisplayName("승인 뒤 수정하면 EDITED 로 돌아가 발행이 거부되고(ADVISORY_005), 다시 승인하면 고친 본문이 발행된다")
    void editAfterApprovalRequiresReapproval() {
        insertAggregate(100L, AggregateLevel.HIGH, null, true);
        AdvisoryInfo draft = useCase.createDraft(OPERATOR, new AdvisoryDraftCommand(GARAK_1, W40, TITLE, BODY));
        AdvisoryInfo approved = useCase.approve(OPERATOR, draft.id(), draft.version());

        AdvisoryInfo reedited = useCase.edit(OPERATOR, draft.id(), new AdvisoryEditCommand(approved.version(), TITLE, "승인 뒤 고친 본문", null));

        assertThat(reedited.status()).isEqualTo(AdvisoryStatus.EDITED);
        assertErrorCode(() -> useCase.publish(OPERATOR, draft.id(), reedited.version()), AdvisoryErrorCode.INVALID_TRANSITION);
        AdvisoryInfo reapproved = useCase.approve(OPERATOR, draft.id(), reedited.version());
        AdvisoryInfo published = useCase.publish(OPERATOR, draft.id(), reapproved.version());
        assertThat(published.body()).isEqualTo("승인 뒤 고친 본문");
        assertThat(useCase.getDetail(draft.id()).histories()).extracting(AdvisoryHistoryInfo::toStatus).containsExactly(
            AdvisoryStatus.DRAFT, AdvisoryStatus.APPROVED, AdvisoryStatus.EDITED, AdvisoryStatus.APPROVED, AdvisoryStatus.PUBLISHED);
    }

    @ParameterizedTest(name = "{0} 에서 {1}")
    @MethodSource("transitionCases")
    @DisplayName("유스케이스 전이 표 — 상태마다 네 처리(수정 · 승인 · 발행 · 철회)를 보내 표에 있는 것만 되고 나머지는 ADVISORY_005 로 아무것도 바꾸지 않는다")
    void operatorTransitionTable(AdvisoryStatus from, Action action, boolean allowed) {
        insertAggregate(100L, AggregateLevel.HIGH, null, true);
        AdvisoryInfo advisory = advisoryIn(from);
        int historyBefore = count("advisory_history");

        if (allowed) {
            assertThat(action.run(useCase, advisory).status()).isEqualTo(action.target);
            assertThat(count("advisory_history")).isEqualTo(historyBefore + 1);
            return;
        }
        assertErrorCode(() -> action.run(useCase, advisory), AdvisoryErrorCode.INVALID_TRANSITION);
        AdvisoryInfo unchanged = useCase.getDetail(advisory.id()).advisory();
        assertThat(unchanged.status()).isEqualTo(from);
        assertThat(unchanged.version()).isEqualTo(advisory.version());
        assertThat(count("advisory_history")).isEqualTo(historyBefore);
    }

    static Stream<Arguments> transitionCases() {
        // AI_DRAFTED 로 가는 경로는 아직 없어 그 상태는 만들 수 없다 — 도메인 표(AdvisoryStatusTest)가 본다.
        return Stream.of(
            Arguments.of(AdvisoryStatus.DRAFT, Action.EDIT, true),
            Arguments.of(AdvisoryStatus.DRAFT, Action.APPROVE, true),
            Arguments.of(AdvisoryStatus.DRAFT, Action.PUBLISH, false),
            Arguments.of(AdvisoryStatus.DRAFT, Action.RETRACT, false),
            Arguments.of(AdvisoryStatus.EDITED, Action.EDIT, true),
            Arguments.of(AdvisoryStatus.EDITED, Action.APPROVE, true),
            Arguments.of(AdvisoryStatus.EDITED, Action.PUBLISH, false),
            Arguments.of(AdvisoryStatus.EDITED, Action.RETRACT, false),
            Arguments.of(AdvisoryStatus.APPROVED, Action.EDIT, true),
            Arguments.of(AdvisoryStatus.APPROVED, Action.APPROVE, false),
            Arguments.of(AdvisoryStatus.APPROVED, Action.PUBLISH, true),
            Arguments.of(AdvisoryStatus.APPROVED, Action.RETRACT, false),
            Arguments.of(AdvisoryStatus.PUBLISHED, Action.EDIT, false),
            Arguments.of(AdvisoryStatus.PUBLISHED, Action.APPROVE, false),
            Arguments.of(AdvisoryStatus.PUBLISHED, Action.PUBLISH, false),
            Arguments.of(AdvisoryStatus.PUBLISHED, Action.RETRACT, true),
            Arguments.of(AdvisoryStatus.RETRACTED, Action.EDIT, false),
            Arguments.of(AdvisoryStatus.RETRACTED, Action.APPROVE, false),
            Arguments.of(AdvisoryStatus.RETRACTED, Action.PUBLISH, false),
            Arguments.of(AdvisoryStatus.RETRACTED, Action.RETRACT, false));
    }

    @Test
    @DisplayName("같은 버전을 읽은 두 운영자 중 늦게 보낸 수정은 ADVISORY_006 이고, 먼저 고친 내용 · 이력만 남는다")
    void staleVersionIsRejected() {
        insertAggregate(100L, AggregateLevel.HIGH, null, true);
        AdvisoryInfo draft = useCase.createDraft(OPERATOR, new AdvisoryDraftCommand(GARAK_1, W40, TITLE, BODY));
        useCase.edit(OPERATOR, draft.id(), new AdvisoryEditCommand(draft.version(), "먼저 고친 제목", BODY, null));

        assertErrorCode(() -> useCase.edit(OTHER_OPERATOR, draft.id(), new AdvisoryEditCommand(draft.version(), "늦게 고친 제목", BODY, null)),
            AdvisoryErrorCode.CONCURRENT_MODIFICATION);
        assertErrorCode(() -> useCase.approve(OTHER_OPERATOR, draft.id(), draft.version()), AdvisoryErrorCode.CONCURRENT_MODIFICATION);

        AdvisoryDetailInfo detail = useCase.getDetail(draft.id());
        assertThat(detail.advisory().title()).isEqualTo("먼저 고친 제목");
        assertThat(detail.advisory().status()).isEqualTo(AdvisoryStatus.EDITED);
        assertThat(detail.histories()).extracting(AdvisoryHistoryInfo::operatorId).containsExactly(OPERATOR, OPERATOR);
    }

    @Test
    @DisplayName("같은 동네 · 주에 발행 중인 안내가 있으면 다른 안내의 발행은 ADVISORY_007 이고, 그 안내를 철회하면 발행된다")
    void onePublishedAdvisoryPerDistrictWeek() {
        insertAggregate(100L, AggregateLevel.HIGH, null, true);
        AdvisoryInfo first = publishedAdvisory();
        AdvisoryInfo second = useCase.createDraft(OPERATOR, new AdvisoryDraftCommand(GARAK_1, W40, "새 안내", BODY));
        AdvisoryInfo secondApproved = useCase.approve(OPERATOR, second.id(), second.version());

        assertErrorCode(() -> useCase.publish(OPERATOR, second.id(), secondApproved.version()), AdvisoryErrorCode.ALREADY_PUBLISHED);

        useCase.retract(OPERATOR, first.id(), new AdvisoryRetractCommand(first.version(), "새 안내로 바꿈"));
        assertThat(useCase.publish(OPERATOR, second.id(), secondApproved.version()).status()).isEqualTo(AdvisoryStatus.PUBLISHED);
    }

    /**
     * 같은 동네 · 주의 서로 다른 두 안내를 동시에 발행한다. 첫 발행은 커밋 전까지 잠금을 쥐고, 두 번째는 그 잠금에서 기다렸다가(H2 세션의
     * {@code BLOCKER_ID} 로 확인) 첫 발행이 커밋한 PUBLISHED 를 보고 거부돼야 한다. 잠그지 않는 존재 확인이면 두 번째가 기다리지 않고 둘 다 발행된다.
     *
     * <p>H2 의 {@code SELECT … FOR UPDATE} 는 행 잠금을 기다린 뒤 최신 커밋 값을 읽어 MySQL InnoDB 의 잠금 읽기(current read)와 같은 결과다. 다만
     * H2 는 읽은 행만 잠그고 InnoDB 처럼 인덱스 범위(gap)를 잠그지 않으며, 잠금 대기 한도도 다르다(H2 {@code LOCK_TIMEOUT} · MySQL
     * {@code innodb_lock_wait_timeout}) — 이 테스트는 줄 서기 결과만 본다.
     */
    @Test
    @DisplayName("같은 동네 · 주의 동시 발행은 잠금에서 줄을 서고, 뒤에 선 쪽은 ADVISORY_007 로 거부된다 — 발행 중인 안내는 하나뿐이다")
    void concurrentPublishIsSerialized() throws Exception {
        insertAggregate(100L, AggregateLevel.HIGH, null, true);
        AdvisoryInfo first = advisoryIn(AdvisoryStatus.APPROVED);
        AdvisoryInfo second = advisoryIn(AdvisoryStatus.APPROVED);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        CountDownLatch firstPublished = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            // 첫 발행: 유스케이스 트랜잭션이 바깥 트랜잭션에 합류해, 발행한 뒤 커밋하지 않고 잠금을 쥔 채 기다린다.
            Future<AdvisoryInfo> firstResult = executor.submit(() -> tx.execute(status -> {
                AdvisoryInfo published = useCase.publish(OPERATOR, first.id(), first.version());
                firstPublished.countDown();
                awaitUninterruptibly(releaseFirst);
                return published;
            }));
            assertThat(firstPublished.await(10, TimeUnit.SECONDS)).isTrue();

            Future<AdvisoryInfo> secondResult = executor.submit(() -> useCase.publish(OTHER_OPERATOR, second.id(), second.version()));
            boolean secondWaited = awaitBlockedSession();
            releaseFirst.countDown();

            assertThat(secondWaited).as("두 번째 발행이 잠금에서 기다렸다").isTrue();
            assertThat(firstResult.get(10, TimeUnit.SECONDS).status()).isEqualTo(AdvisoryStatus.PUBLISHED);
            assertThatThrownBy(() -> secondResult.get(10, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .cause()
                .isInstanceOfSatisfying(AdvisoryException.class, e -> assertThat(e.getErrorCode()).isEqualTo(AdvisoryErrorCode.ALREADY_PUBLISHED));
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
        }
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM advisory WHERE status = 'PUBLISHED'", Integer.class)).isEqualTo(1);
        assertThat(useCase.getDetail(second.id()).advisory().status()).isEqualTo(AdvisoryStatus.APPROVED);
    }

    /** 다른 세션의 잠금을 기다리는 H2 세션이 생길 때까지 본다 (최대 5초). H2 LOCK_TIMEOUT 보다 짧은 간격으로 확인한다. */
    private boolean awaitBlockedSession() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            Integer blocked = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.SESSIONS WHERE BLOCKER_ID IS NOT NULL", Integer.class);
            if (blocked != null && blocked > 0) {
                return true;
            }
            TimeUnit.MILLISECONDS.sleep(10);
        }
        return false;
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    @DisplayName("없는 안내는 처리 · 조회 모두 ADVISORY_001 이다")
    void unknownAdvisory() {
        assertErrorCode(() -> useCase.approve(OPERATOR, 404L, 0L), AdvisoryErrorCode.ADVISORY_NOT_FOUND);
        assertErrorCode(() -> useCase.getDetail(404L), AdvisoryErrorCode.ADVISORY_NOT_FOUND);
    }

    @Test
    @DisplayName("철회 사유는 비울 수 없다")
    void retractReasonRequired() {
        assertThatThrownBy(() -> new AdvisoryRetractCommand(0L, " ")).isInstanceOf(IllegalArgumentException.class);
    }

    /** 운영자 처리 하나. 화면이 읽은 버전을 그대로 싣는다. */
    enum Action {
        EDIT(AdvisoryStatus.EDITED, (useCase, advisory) -> useCase.edit(OPERATOR, advisory.id(),
            new AdvisoryEditCommand(advisory.version(), advisory.title(), "다시 고친 본문", null))),
        APPROVE(AdvisoryStatus.APPROVED, (useCase, advisory) -> useCase.approve(OPERATOR, advisory.id(), advisory.version())),
        PUBLISH(AdvisoryStatus.PUBLISHED, (useCase, advisory) -> useCase.publish(OPERATOR, advisory.id(), advisory.version())),
        RETRACT(AdvisoryStatus.RETRACTED, (useCase, advisory) -> useCase.retract(OPERATOR, advisory.id(),
            new AdvisoryRetractCommand(advisory.version(), "철회 사유")));

        private final AdvisoryStatus target;
        private final ActionCall call;

        Action(AdvisoryStatus target, ActionCall call) {
            this.target = target;
            this.call = call;
        }

        AdvisoryInfo run(AdvisoryOperatorUseCase useCase, AdvisoryInfo advisory) {
            return call.run(useCase, advisory);
        }
    }

    @FunctionalInterface
    interface ActionCall {
        AdvisoryInfo run(AdvisoryOperatorUseCase useCase, AdvisoryInfo advisory);
    }

    /** {@code status} 까지 유스케이스로 몰고 간 안내. */
    private AdvisoryInfo advisoryIn(AdvisoryStatus status) {
        AdvisoryInfo draft = useCase.createDraft(OPERATOR, new AdvisoryDraftCommand(GARAK_1, W40, TITLE, BODY));
        Function<AdvisoryInfo, AdvisoryInfo> approve = advisory -> useCase.approve(OPERATOR, advisory.id(), advisory.version());
        Function<AdvisoryInfo, AdvisoryInfo> publish = advisory -> useCase.publish(OPERATOR, advisory.id(), advisory.version());
        return switch (status) {
            case DRAFT -> draft;
            case EDITED -> useCase.edit(OPERATOR, draft.id(), new AdvisoryEditCommand(draft.version(), TITLE, "고친 본문", null));
            case APPROVED -> approve.apply(draft);
            case PUBLISHED -> approve.andThen(publish).apply(draft);
            case RETRACTED -> {
                AdvisoryInfo published = approve.andThen(publish).apply(draft);
                yield useCase.retract(OPERATOR, draft.id(), new AdvisoryRetractCommand(published.version(), "철회 사유"));
            }
            case AI_DRAFTED -> throw new IllegalArgumentException("AI 초안 경로는 아직 없다");
        };
    }

    private AdvisoryInfo publishedAdvisory() {
        return advisoryIn(AdvisoryStatus.PUBLISHED);
    }

    private static void assertErrorCode(ThrowingCallable call, AdvisoryErrorCode errorCode) {
        assertThatThrownBy(call).isInstanceOfSatisfying(AdvisoryException.class, e -> assertThat(e.getErrorCode()).isEqualTo(errorCode));
    }

    private int count(String table) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return count == null ? 0 : count;
    }

    /** 가락1동 · 2026-W40 집계 한 행 (참여 150 · 증상 30 · 호흡기 20 · 장관 15). 자료 부족이면 기준선 없이 이유만 둔다. */
    private void insertAggregate(long id, AggregateLevel level, String insufficientReason, boolean finalized) {
        boolean insufficient = level == AggregateLevel.INSUFFICIENT;
        jdbcTemplate.update("""
                INSERT INTO district_weekly_aggregate (id, district_code, iso_week, participant_count, symptomatic_count, respiratory_count, enteric_count,
                  revised_report_count, level, insufficient_reason, baseline_participant_count, baseline_symptomatic_count, rule_version, calculated_at,
                  finalized_at, created_at, updated_at)
                VALUES (?, ?, ?, 150, 30, 20, 15, 2, ?, ?, ?, ?, '2026-10-08', ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)""",
            id, GARAK_1, W40.value(), level.name(), insufficientReason, insufficient ? null : 600, insufficient ? null : 60, Timestamp.valueOf(FINALIZED_AT),
            finalized ? Timestamp.valueOf(FINALIZED_AT) : null);
    }
}
