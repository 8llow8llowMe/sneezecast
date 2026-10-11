package com.sneezecast.domainlayer.advisory.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.sneezecast.SurveillanceH2TestSupport;
import com.sneezecast.common.dto.metadata.CodeNameDescriptionMetadata;
import com.sneezecast.domainlayer.advisory.adapter.in.web.dto.item.AdvisoryHistoryItem;
import com.sneezecast.domainlayer.advisory.adapter.in.web.dto.item.AdvisoryOperatorCitationItem;
import com.sneezecast.domainlayer.advisory.adapter.in.web.dto.response.AdvisoryOperatorDetailResponse;
import com.sneezecast.domainlayer.advisory.adapter.in.web.dto.response.AdvisoryOperatorResponse;
import com.sneezecast.domainlayer.advisory.application.command.AdvisoryCorrectCommand;
import com.sneezecast.domainlayer.advisory.application.command.AdvisoryDraftCommand;
import com.sneezecast.domainlayer.advisory.application.command.AdvisoryEditCommand;
import com.sneezecast.domainlayer.advisory.application.command.AdvisoryRetractCommand;
import com.sneezecast.domainlayer.advisory.application.exception.AdvisoryErrorCode;
import com.sneezecast.domainlayer.advisory.application.exception.AdvisoryException;
import com.sneezecast.domainlayer.advisory.application.port.in.AdvisoryOperatorWebUseCase;
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
class AdvisoryOperatorWebFacadeTest extends SurveillanceH2TestSupport {

    private static final String GARAK_1 = "11240660";
    private static final ReportWeek W40 = ReportWeek.parse("2026-W40");
    private static final long OPERATOR = 9001L;
    private static final long OTHER_OPERATOR = 9002L;
    private static final LocalDateTime FINALIZED_AT = LocalDateTime.of(2026, 10, 5, 0, 10);
    private static final String TITLE = "가락1동 호흡기 증상 보고가 늘었어요";
    private static final String BODY = "손 씻기와 기침 예절을 지켜 주세요.";

    @Autowired
    private AdvisoryOperatorWebUseCase useCase;

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

        AdvisoryOperatorResponse draft = useCase.createDraft(OPERATOR, new AdvisoryDraftCommand(GARAK_1, W40, TITLE, BODY));

        assertThat(draft.status()).isEqualTo(AdvisoryStatus.DRAFT.toMetadata());
        assertThat(draft.version()).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT aggregate_id FROM advisory", Long.class)).isEqualTo(100L);
        assertThat(citation(draft)).isEqualTo(new AdvisoryCitation(150, 30, 20, 15, AggregateLevel.SLIGHT, "2026-10-08"));
        assertThat(useCase.getDetail(id(draft)).histories()).singleElement().satisfies(history -> {
            assertThat(history.fromStatus()).isNull();
            assertThat(history.toStatus()).isEqualTo(AdvisoryStatus.DRAFT.toMetadata());
            assertThat(history.operatorId()).isEqualTo(String.valueOf(OPERATOR));
            assertThat(history).extracting(AdvisoryHistoryItem::title, AdvisoryHistoryItem::body).containsExactly(TITLE, BODY);
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
        AdvisoryOperatorResponse draft = useCase.createDraft(OPERATOR, new AdvisoryDraftCommand(GARAK_1, W40, TITLE, BODY));

        // 마감된 행은 재계산이 고치지 않지만, 어떤 경로로든 집계가 바뀌어도 안내가 따라가지 않는지 본다.
        jdbcTemplate.update("UPDATE district_weekly_aggregate SET participant_count = 400, symptomatic_count = 90, respiratory_count = 60,"
            + " enteric_count = 40, level = 'HIGH', rule_version = '2027-01-01' WHERE id = 100");
        AdvisoryOperatorResponse approved = useCase.approve(OPERATOR, id(draft), draft.version());

        assertThat(citation(approved)).isEqualTo(new AdvisoryCitation(150, 30, 20, 15, AggregateLevel.SLIGHT, "2026-10-08"));
        assertThat(useCase.getDetail(id(draft)).advisory().citation()).isEqualTo(approved.citation());
    }

    @Test
    @DisplayName("초안 → 수정 → 승인 → 발행 → 철회가 버전을 하나씩 올리고, 전이마다 운영자 · 메모와 함께 이력이 한 행씩 쌓인다")
    void fullLifecycleRecordsHistory() {
        insertAggregate(100L, AggregateLevel.HIGH, null, true);
        AdvisoryOperatorResponse draft = useCase.createDraft(OPERATOR, new AdvisoryDraftCommand(GARAK_1, W40, TITLE, BODY));

        AdvisoryOperatorResponse edited = useCase.edit(OTHER_OPERATOR, id(draft), new AdvisoryEditCommand(0L, "고친 제목", "고친 본문", "문장 다듬기"));
        AdvisoryOperatorResponse approved = useCase.approve(OPERATOR, id(draft), edited.version());
        AdvisoryOperatorResponse published = useCase.publish(OPERATOR, id(draft), approved.version());
        AdvisoryOperatorResponse retracted = useCase.retract(OTHER_OPERATOR, id(draft), new AdvisoryRetractCommand(published.version(), "집계 오류 확인"));

        assertThat(List.of(edited, approved, published, retracted)).extracting(response -> status(response.status()), AdvisoryOperatorResponse::version).containsExactly(
            tuple(AdvisoryStatus.EDITED, 1L), tuple(AdvisoryStatus.APPROVED, 2L), tuple(AdvisoryStatus.PUBLISHED, 3L), tuple(AdvisoryStatus.RETRACTED, 4L));
        assertThat(published.publishedAt()).isNotNull();
        assertThat(published.publishedAt().getNano()).as("초 단위 — MySQL TIMESTAMP 와 같다").isZero();
        assertThat(retracted.retractedAt()).isNotNull();
        assertThat(retracted.publishedAt()).isEqualTo(published.publishedAt());

        AdvisoryOperatorDetailResponse detail = useCase.getDetail(id(draft));
        assertThat(detail.advisory().status()).isEqualTo(AdvisoryStatus.RETRACTED.toMetadata());
        assertThat(detail.histories())
            .extracting(history -> status(history.fromStatus()), history -> status(history.toStatus()), history -> Long.parseLong(history.operatorId()),
                AdvisoryHistoryItem::memo)
            .containsExactly(
                tuple(null, AdvisoryStatus.DRAFT, OPERATOR, null),
                tuple(AdvisoryStatus.DRAFT, AdvisoryStatus.EDITED, OTHER_OPERATOR, "문장 다듬기"),
                tuple(AdvisoryStatus.EDITED, AdvisoryStatus.APPROVED, OPERATOR, null),
                tuple(AdvisoryStatus.APPROVED, AdvisoryStatus.PUBLISHED, OPERATOR, null),
                tuple(AdvisoryStatus.PUBLISHED, AdvisoryStatus.RETRACTED, OTHER_OPERATOR, "집계 오류 확인"));
        // 이력의 제목 · 본문은 그 시점의 값이다 — 승인 · 발행된 본문을 복원할 수 있다.
        assertThat(detail.histories().get(3)).extracting(AdvisoryHistoryItem::title, AdvisoryHistoryItem::body).containsExactly("고친 제목", "고친 본문");
        assertThat(detail.histories().get(0)).extracting(AdvisoryHistoryItem::title, AdvisoryHistoryItem::body).containsExactly(TITLE, BODY);
    }

    @Test
    @DisplayName("승인 뒤 수정하면 EDITED 로 돌아가 발행이 거부되고(ADVISORY_005), 다시 승인하면 고친 본문이 발행된다")
    void editAfterApprovalRequiresReapproval() {
        insertAggregate(100L, AggregateLevel.HIGH, null, true);
        AdvisoryOperatorResponse draft = useCase.createDraft(OPERATOR, new AdvisoryDraftCommand(GARAK_1, W40, TITLE, BODY));
        AdvisoryOperatorResponse approved = useCase.approve(OPERATOR, id(draft), draft.version());

        AdvisoryOperatorResponse reedited = useCase.edit(OPERATOR, id(draft), new AdvisoryEditCommand(approved.version(), TITLE, "승인 뒤 고친 본문", null));

        assertThat(reedited.status()).isEqualTo(AdvisoryStatus.EDITED.toMetadata());
        assertErrorCode(() -> useCase.publish(OPERATOR, id(draft), reedited.version()), AdvisoryErrorCode.INVALID_TRANSITION);
        AdvisoryOperatorResponse reapproved = useCase.approve(OPERATOR, id(draft), reedited.version());
        AdvisoryOperatorResponse published = useCase.publish(OPERATOR, id(draft), reapproved.version());
        assertThat(published.body()).isEqualTo("승인 뒤 고친 본문");
        assertThat(useCase.getDetail(id(draft)).histories()).extracting(history -> status(history.toStatus())).containsExactly(
            AdvisoryStatus.DRAFT, AdvisoryStatus.APPROVED, AdvisoryStatus.EDITED, AdvisoryStatus.APPROVED, AdvisoryStatus.PUBLISHED);
    }

    @ParameterizedTest(name = "{0} 에서 {1}")
    @MethodSource("transitionCases")
    @DisplayName("유스케이스 전이 표 — 상태마다 네 처리(수정 · 승인 · 발행 · 철회)를 보내 표에 있는 것만 되고 나머지는 ADVISORY_005 로 아무것도 바꾸지 않는다")
    void operatorTransitionTable(AdvisoryStatus from, Action action, boolean allowed) {
        insertAggregate(100L, AggregateLevel.HIGH, null, true);
        AdvisoryOperatorResponse advisory = advisoryIn(from);
        int historyBefore = count("advisory_history");

        if (allowed) {
            assertThat(action.run(useCase, advisory).status()).isEqualTo(action.target.toMetadata());
            assertThat(count("advisory_history")).isEqualTo(historyBefore + 1);
            return;
        }
        assertErrorCode(() -> action.run(useCase, advisory), AdvisoryErrorCode.INVALID_TRANSITION);
        AdvisoryOperatorResponse unchanged = useCase.getDetail(id(advisory)).advisory();
        assertThat(unchanged.status()).isEqualTo(from.toMetadata());
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
        AdvisoryOperatorResponse draft = useCase.createDraft(OPERATOR, new AdvisoryDraftCommand(GARAK_1, W40, TITLE, BODY));
        useCase.edit(OPERATOR, id(draft), new AdvisoryEditCommand(draft.version(), "먼저 고친 제목", BODY, null));

        assertErrorCode(() -> useCase.edit(OTHER_OPERATOR, id(draft), new AdvisoryEditCommand(draft.version(), "늦게 고친 제목", BODY, null)),
            AdvisoryErrorCode.CONCURRENT_MODIFICATION);
        assertErrorCode(() -> useCase.approve(OTHER_OPERATOR, id(draft), draft.version()), AdvisoryErrorCode.CONCURRENT_MODIFICATION);

        AdvisoryOperatorDetailResponse detail = useCase.getDetail(id(draft));
        assertThat(detail.advisory().title()).isEqualTo("먼저 고친 제목");
        assertThat(detail.advisory().status()).isEqualTo(AdvisoryStatus.EDITED.toMetadata());
        assertThat(detail.histories()).extracting(history -> Long.parseLong(history.operatorId())).containsExactly(OPERATOR, OPERATOR);
    }

    @Test
    @DisplayName("같은 동네 · 주에 발행 중인 안내가 있으면 다른 안내의 발행은 ADVISORY_007 이고, 그 안내를 철회하면 발행된다")
    void onePublishedAdvisoryPerDistrictWeek() {
        insertAggregate(100L, AggregateLevel.HIGH, null, true);
        AdvisoryOperatorResponse first = publishedAdvisory();
        AdvisoryOperatorResponse second = useCase.createDraft(OPERATOR, new AdvisoryDraftCommand(GARAK_1, W40, "새 안내", BODY));
        AdvisoryOperatorResponse secondApproved = useCase.approve(OPERATOR, id(second), second.version());

        assertErrorCode(() -> useCase.publish(OPERATOR, id(second), secondApproved.version()), AdvisoryErrorCode.ALREADY_PUBLISHED);

        useCase.retract(OPERATOR, id(first), new AdvisoryRetractCommand(first.version(), "새 안내로 바꿈"));
        assertThat(useCase.publish(OPERATOR, id(second), secondApproved.version()).status()).isEqualTo(AdvisoryStatus.PUBLISHED.toMetadata());
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
        AdvisoryOperatorResponse first = advisoryIn(AdvisoryStatus.APPROVED);
        AdvisoryOperatorResponse second = advisoryIn(AdvisoryStatus.APPROVED);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        CountDownLatch firstPublished = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            // 첫 발행: 유스케이스 트랜잭션이 바깥 트랜잭션에 합류해, 발행한 뒤 커밋하지 않고 잠금을 쥔 채 기다린다.
            Future<AdvisoryOperatorResponse> firstResult = executor.submit(() -> tx.execute(status -> {
                AdvisoryOperatorResponse published = useCase.publish(OPERATOR, id(first), first.version());
                firstPublished.countDown();
                awaitUninterruptibly(releaseFirst);
                return published;
            }));
            assertThat(firstPublished.await(10, TimeUnit.SECONDS)).isTrue();

            Future<AdvisoryOperatorResponse> secondResult = executor.submit(() -> useCase.publish(OTHER_OPERATOR, id(second), second.version()));
            boolean secondWaited = awaitBlockedSession();
            releaseFirst.countDown();

            assertThat(secondWaited).as("두 번째 발행이 잠금에서 기다렸다").isTrue();
            assertThat(firstResult.get(10, TimeUnit.SECONDS).status()).isEqualTo(AdvisoryStatus.PUBLISHED.toMetadata());
            assertThatThrownBy(() -> secondResult.get(10, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .cause()
                .isInstanceOfSatisfying(AdvisoryException.class, e -> assertThat(e.getErrorCode()).isEqualTo(AdvisoryErrorCode.ALREADY_PUBLISHED));
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
        }
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM advisory WHERE status = 'PUBLISHED'", Integer.class)).isEqualTo(1);
        assertThat(useCase.getDetail(id(second)).advisory().status()).isEqualTo(AdvisoryStatus.APPROVED.toMetadata());
    }

    @Test
    @DisplayName("정정 발행은 한 번에 — 이전 안내 RETRACTED(사유 메모) + 새 안내 PUBLISHED(인용 수치 복사 · 이전 안내를 가리킴), 이력은 철회 → (없음) → APPROVED → PUBLISHED")
    void correctRetractsAndPublishesTogether() {
        insertAggregate(100L, AggregateLevel.HIGH, null, true);
        AdvisoryOperatorResponse published = publishedAdvisory();
        // 정정은 이전 안내의 인용값을 옮긴다 — 그 사이 집계가 바뀌어도 운영자가 검토한 근거를 잇는다.
        jdbcTemplate.update("UPDATE district_weekly_aggregate SET participant_count = 400, symptomatic_count = 90, respiratory_count = 60,"
            + " enteric_count = 40, rule_version = '2027-01-01' WHERE id = 100");

        AdvisoryOperatorResponse correction = useCase.correct(OTHER_OPERATOR, id(published),
            new AdvisoryCorrectCommand(published.version(), "고친 제목", "고친 본문", "참여자 수 표기 오류"));

        assertThat(correction.advisoryId()).isNotEqualTo(published.advisoryId());
        assertThat(status(correction.status())).isEqualTo(AdvisoryStatus.PUBLISHED);
        assertThat(correction.version()).as("insert 0 → 발행 1").isEqualTo(1L);
        assertThat(correction.correctsAdvisoryId()).isEqualTo(published.advisoryId());
        assertThat(correction).extracting(AdvisoryOperatorResponse::districtCode, AdvisoryOperatorResponse::isoWeek, AdvisoryOperatorResponse::title,
            AdvisoryOperatorResponse::body).containsExactly(GARAK_1, W40.value(), "고친 제목", "고친 본문");
        assertThat(citation(correction)).isEqualTo(citation(published));
        assertThat(jdbcTemplate.queryForList("SELECT aggregate_id FROM advisory", Long.class)).containsOnly(100L);

        AdvisoryOperatorDetailResponse previous = useCase.getDetail(id(published));
        assertThat(status(previous.advisory().status())).isEqualTo(AdvisoryStatus.RETRACTED);
        assertThat(previous.advisory().version()).isEqualTo(published.version() + 1);
        assertThat(previous.advisory().retractedAt()).as("철회 시각 = 새 안내의 발행 시각(정정 시각)").isEqualTo(correction.publishedAt());
        assertThat(previous.histories().getLast())
            .extracting(history -> status(history.fromStatus()), history -> status(history.toStatus()), history -> Long.parseLong(history.operatorId()),
                AdvisoryHistoryItem::memo)
            .containsExactly(AdvisoryStatus.PUBLISHED, AdvisoryStatus.RETRACTED, OTHER_OPERATOR, "참여자 수 표기 오류");
        assertThat(useCase.getDetail(id(correction)).histories())
            .extracting(history -> status(history.fromStatus()), history -> status(history.toStatus()), history -> Long.parseLong(history.operatorId()),
                AdvisoryHistoryItem::memo, AdvisoryHistoryItem::body)
            .containsExactly(
                tuple(null, AdvisoryStatus.APPROVED, OTHER_OPERATOR, "참여자 수 표기 오류", "고친 본문"),
                tuple(AdvisoryStatus.APPROVED, AdvisoryStatus.PUBLISHED, OTHER_OPERATOR, null, "고친 본문"));
        // 한 요청이 쌓은 세 행의 순서 — 철회가 먼저다.
        assertThat(jdbcTemplate.queryForList("SELECT to_status FROM advisory_history WHERE operator_id = ? ORDER BY created_at, id", String.class,
            OTHER_OPERATOR)).containsExactly("RETRACTED", "APPROVED", "PUBLISHED");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM advisory WHERE status = 'PUBLISHED'", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("정정의 정정 — 새 안내도 발행 중이라 다시 정정할 수 있고 사슬이 이어진다")
    void correctionCanBeCorrected() {
        insertAggregate(100L, AggregateLevel.HIGH, null, true);
        AdvisoryOperatorResponse first = publishedAdvisory();
        AdvisoryOperatorResponse second = useCase.correct(OPERATOR, id(first), new AdvisoryCorrectCommand(first.version(), TITLE, "두 번째 본문", "첫 정정"));

        AdvisoryOperatorResponse third = useCase.correct(OPERATOR, id(second), new AdvisoryCorrectCommand(second.version(), TITLE, "세 번째 본문", "두 번째 정정"));

        assertThat(third.correctsAdvisoryId()).isEqualTo(second.advisoryId());
        assertThat(status(useCase.getDetail(id(second)).advisory().status())).isEqualTo(AdvisoryStatus.RETRACTED);
        assertThat(jdbcTemplate.queryForList("SELECT status FROM advisory ORDER BY id", String.class)).containsExactly("RETRACTED", "RETRACTED", "PUBLISHED");
    }

    @Test
    @DisplayName("정정 거부는 아무것도 바꾸지 않는다 — 없음 001 · 옛 버전 006 · 발행 중이 아님(승인 · 철회) 005 · 같은 동네 · 주에 다른 발행 중 안내 007")
    void correctRejectionsChangeNothing() {
        insertAggregate(100L, AggregateLevel.HIGH, null, true);
        // 철회된 안내를 먼저 만든다 — 발행 중인 안내가 있으면 그 발행이 ADVISORY_007 이다.
        AdvisoryOperatorResponse retracted = advisoryIn(AdvisoryStatus.RETRACTED);
        AdvisoryOperatorResponse published = publishedAdvisory();
        AdvisoryOperatorResponse approved = advisoryIn(AdvisoryStatus.APPROVED);
        List<String> before = advisorySnapshot();
        int historyBefore = count("advisory_history");

        assertErrorCode(() -> useCase.correct(OPERATOR, 404L, correctCommand(0L)), AdvisoryErrorCode.ADVISORY_NOT_FOUND);
        assertErrorCode(() -> useCase.correct(OPERATOR, id(published), correctCommand(published.version() - 1)), AdvisoryErrorCode.CONCURRENT_MODIFICATION);
        assertErrorCode(() -> useCase.correct(OPERATOR, id(approved), correctCommand(approved.version())), AdvisoryErrorCode.INVALID_TRANSITION);
        assertErrorCode(() -> useCase.correct(OPERATOR, id(retracted), correctCommand(retracted.version())), AdvisoryErrorCode.INVALID_TRANSITION);
        assertThat(advisorySnapshot()).isEqualTo(before);
        assertThat(count("advisory_history")).isEqualTo(historyBefore);

        // 같은 동네 · 주에 발행 중인 안내가 둘인 행(규칙 위반 데이터)에서 정정하면 발행 중이 둘로 남는다 — 막는다.
        jdbcTemplate.update("UPDATE advisory SET status = 'PUBLISHED', published_at = ? WHERE id = ?", Timestamp.valueOf(FINALIZED_AT), id(approved));
        List<String> violated = advisorySnapshot();
        assertErrorCode(() -> useCase.correct(OPERATOR, id(published), correctCommand(published.version())), AdvisoryErrorCode.ALREADY_PUBLISHED);

        assertThat(advisorySnapshot()).isEqualTo(violated);
        assertThat(count("advisory_history")).isEqualTo(historyBefore);
    }

    @Test
    @DisplayName("철회를 저장한 뒤 새 안내 단계에서 실패해도 철회까지 함께 롤백된다 — 이전 안내는 발행 중 그대로, 새 행 · 이력 없음")
    void correctFailureAfterRetractRollsBackEverything() {
        insertAggregate(100L, AggregateLevel.HIGH, null, true);
        AdvisoryOperatorResponse published = publishedAdvisory();
        List<String> before = advisorySnapshot();
        int historyBefore = count("advisory_history");

        // 101자 제목 — 웹 경계(ADVISORY_106)를 거치지 않으면 철회를 flush 한 뒤 새 안내를 만들 때 도메인이 거부한다.
        assertThatThrownBy(() -> useCase.correct(OPERATOR, id(published), new AdvisoryCorrectCommand(published.version(), "가".repeat(101), BODY, "사유")))
            .isInstanceOf(IllegalArgumentException.class);

        assertThat(advisorySnapshot()).isEqualTo(before);
        assertThat(count("advisory_history")).isEqualTo(historyBefore);
        assertThat(useCase.getDetail(id(published)).advisory().status()).isEqualTo(AdvisoryStatus.PUBLISHED.toMetadata());
    }

    private static AdvisoryCorrectCommand correctCommand(long version) {
        return new AdvisoryCorrectCommand(version, TITLE, "고친 본문", "정정 사유");
    }

    /** 안내 행마다 {@code id:status:version} — 거부 · 롤백 전후 비교용. */
    private List<String> advisorySnapshot() {
        return jdbcTemplate.queryForList("SELECT CONCAT(id, ':', status, ':', version) FROM advisory ORDER BY id", String.class);
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
        EDIT(AdvisoryStatus.EDITED, (useCase, advisory) -> useCase.edit(OPERATOR, id(advisory),
            new AdvisoryEditCommand(advisory.version(), advisory.title(), "다시 고친 본문", null))),
        APPROVE(AdvisoryStatus.APPROVED, (useCase, advisory) -> useCase.approve(OPERATOR, id(advisory), advisory.version())),
        PUBLISH(AdvisoryStatus.PUBLISHED, (useCase, advisory) -> useCase.publish(OPERATOR, id(advisory), advisory.version())),
        RETRACT(AdvisoryStatus.RETRACTED, (useCase, advisory) -> useCase.retract(OPERATOR, id(advisory),
            new AdvisoryRetractCommand(advisory.version(), "철회 사유")));

        private final AdvisoryStatus target;
        private final ActionCall call;

        Action(AdvisoryStatus target, ActionCall call) {
            this.target = target;
            this.call = call;
        }

        AdvisoryOperatorResponse run(AdvisoryOperatorWebUseCase useCase, AdvisoryOperatorResponse advisory) {
            return call.run(useCase, advisory);
        }
    }

    @FunctionalInterface
    interface ActionCall {
        AdvisoryOperatorResponse run(AdvisoryOperatorWebUseCase useCase, AdvisoryOperatorResponse advisory);
    }

    /** {@code status} 까지 유스케이스로 몰고 간 안내. */
    private AdvisoryOperatorResponse advisoryIn(AdvisoryStatus status) {
        AdvisoryOperatorResponse draft = useCase.createDraft(OPERATOR, new AdvisoryDraftCommand(GARAK_1, W40, TITLE, BODY));
        Function<AdvisoryOperatorResponse, AdvisoryOperatorResponse> approve = advisory -> useCase.approve(OPERATOR, id(advisory), advisory.version());
        Function<AdvisoryOperatorResponse, AdvisoryOperatorResponse> publish = advisory -> useCase.publish(OPERATOR, id(advisory), advisory.version());
        return switch (status) {
            case DRAFT -> draft;
            case EDITED -> useCase.edit(OPERATOR, id(draft), new AdvisoryEditCommand(draft.version(), TITLE, "고친 본문", null));
            case APPROVED -> approve.apply(draft);
            case PUBLISHED -> approve.andThen(publish).apply(draft);
            case RETRACTED -> {
                AdvisoryOperatorResponse published = approve.andThen(publish).apply(draft);
                yield useCase.retract(OPERATOR, id(draft), new AdvisoryRetractCommand(published.version(), "철회 사유"));
            }
            case AI_DRAFTED -> throw new IllegalArgumentException("AI 초안 경로는 아직 없다");
        };
    }

    private AdvisoryOperatorResponse publishedAdvisory() {
        return advisoryIn(AdvisoryStatus.PUBLISHED);
    }

    private static long id(AdvisoryOperatorResponse advisory) {
        return Long.parseLong(advisory.advisoryId());
    }

    /** 응답의 상태 metadata 를 enum 으로 되돌린다. 생성 이력의 이전 상태(null)는 null 이다. */
    private static AdvisoryStatus status(CodeNameDescriptionMetadata metadata) {
        return metadata == null ? null : AdvisoryStatus.valueOf(metadata.code());
    }

    /** 응답의 인용 수치를 도메인 값으로 되돌린다 — 집계에서 복사한 값과 한 번에 비교한다. */
    private static AdvisoryCitation citation(AdvisoryOperatorResponse advisory) {
        AdvisoryOperatorCitationItem item = advisory.citation();
        return new AdvisoryCitation(item.participantCount(), item.symptomaticCount(), item.respiratoryCount(), item.entericCount(),
            AggregateLevel.valueOf(item.level().code()), item.ruleVersion());
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
