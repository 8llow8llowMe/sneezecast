package com.sneezecast.domainlayer.sentinelimport.application.service;

import com.sneezecast.domainlayer.official.application.exception.OfficialIngestErrorCode;
import com.sneezecast.domainlayer.official.application.exception.OfficialIngestException;
import com.sneezecast.domainlayer.official.application.model.OfficialIngestResult;
import com.sneezecast.domainlayer.official.application.service.processor.OfficialIngestProcessor;
import com.sneezecast.domainlayer.official.domain.enums.IngestChannel;
import com.sneezecast.domainlayer.official.domain.enums.OfficialSource;
import com.sneezecast.domainlayer.official.domain.model.OfficialRecord;
import com.sneezecast.domainlayer.official.domain.model.OfficialSnapshotDraft;
import com.sneezecast.domainlayer.sentinelimport.application.command.SentinelImportCommand;
import com.sneezecast.domainlayer.sentinelimport.application.exception.SentinelImportErrorCode;
import com.sneezecast.domainlayer.sentinelimport.application.exception.SentinelImportException;
import com.sneezecast.domainlayer.sentinelimport.application.model.SentinelCallBudget;
import com.sneezecast.domainlayer.sentinelimport.application.model.SentinelImportResult;
import com.sneezecast.domainlayer.sentinelimport.application.port.in.SentinelImportUseCase;
import com.sneezecast.domainlayer.sentinelimport.application.port.out.SentinelSourcePort;
import com.sneezecast.domainlayer.sentinelimport.application.service.processor.SentinelRecordProcessor;
import com.sneezecast.domainlayer.sentinelimport.domain.model.SentinelFetch;
import com.sneezecast.domainlayer.sentinelimport.domain.model.SentinelIliRow;
import com.sneezecast.domainlayer.sentinelimport.domain.model.SentinelImportPlan;
import com.sneezecast.domainlayer.sentinelimport.domain.model.SentinelPathogenRow;
import com.sneezecast.domainlayer.sentinelimport.domain.model.SentinelRequest;
import com.sneezecast.global.properties.SentinelImportProperties;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 감염병포털 표본감시 적재 오케스트레이터 (entity-design §3-2 · §3-3 · §4-1, data-api-analysis §3).
 *
 * <p>순서: 계획({@link SentinelImportPlan}, 3 ~ 4건) 순서대로 요청마다 [원천 호출 → 행 변환 → {@link OfficialIngestProcessor#ingest}].
 * 원천 호출 · 변환 실패는 여기서 {@link OfficialIngestProcessor#recordFailure} 로 FAILED 이력을 남기고, 검증 · 쓰기 실패는 프로세서가 남긴다 —
 * 시도한 요청은 성공 · 실패와 무관하게 이력 한 행을 남긴다. 0행 응답(시작 전 절기 등)도 IMPORTED 이력(imported 0)이다.
 *
 * <p><b>요청별 격리.</b> 실패한 요청은 이력만 남기고 다음 요청으로 간다. 형식 변경({@code SCHEMA_CHANGED})도 그 요청만 멈춘다 — 프로그램마다
 * 열 구성이 달라 인플루엔자 형식이 바뀌어도 급성호흡기 · 장관감염증은 받을 수 있다. 남은 요청을 부르지 않고 멈추는 것은 계속해도 소용없거나
 * 원천에 부담만 주는 경우뿐이다.
 * <ul>
 *   <li>실행당 호출 상한({@link #ABORTING_SOURCE_ERRORS}) — 이후 요청도 호출 전에 막힌다.</li>
 *   <li>쓰기 실패(검증 실패가 아닌 {@code ingest} 예외)와 FAILED 이력 쓰기 실패(원천 · 변환 실패든 검증 실패든) — DB 문제면 이후 쓰기도
 *       실패한다.</li>
 * </ul>
 * 멈춘 요청 자체는 FAILED 이력을 남기고, 부르지 않은 요청은 이력이 없다. 끝에 실패가 하나라도 있으면 {@code RUN_FAILED} 로 Step · Job 을
 * FAILED 로 끝낸다.
 *
 * <p><b>트랜잭션을 걸지 않고 DB 를 직접 만지지 않는다.</b> 원천 호출(요청마다 화면 + 데이터, 간격 3초 이상)을 트랜잭션 안에서 기다리면 그동안
 * 커넥션을 쥔다 (architecture-guide §3-1). 쓰기는 전부 {@link OfficialIngestProcessor} 가 요청마다 primary 매니저의 트랜잭션으로 열고 닫는다
 * ({@code NotifiableImportFacade} 와 같다).
 *
 * <p><b>시각 출처는 하나다.</b> {@code syncedAt} 은 실행 시작 때 한 번 정해 모든 요청의 행 · 이력에 쓴다. {@code run_started_at} 은 JobParameter
 * {@code runAt} 이다.
 */
@Slf4j
@Service
public class SentinelImportFacade implements SentinelImportUseCase {

    /** 이 원천 오류가 나면 남은 요청을 부르지 않는다 — 호출 상한이라 이후 요청도 호출 전에 막힌다. */
    static final Set<SentinelImportErrorCode> ABORTING_SOURCE_ERRORS = EnumSet.of(SentinelImportErrorCode.REQUEST_BUDGET_EXCEEDED);

    /** {@code RUN_FAILED} 메시지에 싣는 실패 request_key 수. EXIT_MESSAGE 가 불어나지 않게 한다. */
    static final int FAILED_KEYS_MESSAGE_LIMIT = 20;

    private static final String NO_ABORT = "-";

    private final SentinelSourcePort sentinelSourcePort;
    private final SentinelRecordProcessor sentinelRecordProcessor;
    private final OfficialIngestProcessor officialIngestProcessor;
    private final SentinelImportProperties sentinelImportProperties;
    private final Clock clock;

    @Autowired
    public SentinelImportFacade(
        SentinelSourcePort sentinelSourcePort,
        SentinelRecordProcessor sentinelRecordProcessor,
        OfficialIngestProcessor officialIngestProcessor,
        SentinelImportProperties sentinelImportProperties
    ) {
        this(sentinelSourcePort, sentinelRecordProcessor, officialIngestProcessor, sentinelImportProperties, Clock.systemDefaultZone());
    }

    /**
     * @param clock {@code syncedAt} 의 출처. 행의 시각 컬럼은 JVM 시각이다 (surveillance {@code BaseEntity} 와 같다)
     */
    SentinelImportFacade(
        SentinelSourcePort sentinelSourcePort,
        SentinelRecordProcessor sentinelRecordProcessor,
        OfficialIngestProcessor officialIngestProcessor,
        SentinelImportProperties sentinelImportProperties,
        Clock clock
    ) {
        this.sentinelSourcePort = sentinelSourcePort;
        this.sentinelRecordProcessor = sentinelRecordProcessor;
        this.officialIngestProcessor = officialIngestProcessor;
        this.sentinelImportProperties = sentinelImportProperties;
        this.clock = clock;
    }

    @Override
    public SentinelImportResult importSentinel(SentinelImportCommand command) {
        LocalDateTime syncedAt = LocalDateTime.now(clock);
        SentinelImportPlan plan = SentinelImportPlan.of(command.baseDate(), sentinelImportProperties.recentWeeks());
        SentinelCallBudget budget = new SentinelCallBudget(sentinelImportProperties.maxCallsPerRun());
        RunTally tally = new RunTally();

        for (SentinelRequest request : plan.requests()) {
            tally.attempted++;
            if (!importRequest(request, budget, command.runStartedAt(), syncedAt, tally)) {
                break;
            }
        }

        if (!tally.failedKeys.isEmpty()) {
            throw runFailed(plan, tally, budget);
        }
        log.info("sentinelImportJob run completed. baseDate={} requests={} importedRows={} calls={} nullValues={} pending={}",
            command.baseDate(), tally.importedRequests, tally.importedRows, budget.used(), tally.nullValues, tally.pending);
        return new SentinelImportResult(command.baseDate(), tally.importedRequests, tally.importedRows, budget.used(), tally.nullValues,
            tally.pending);
    }

    /**
     * 요청 하나를 받아 적재한다. 실패는 이력을 남기고 집계에 더한다.
     *
     * @return 다음 요청으로 계속할지. false 면 실행을 멈춘다
     */
    private boolean importRequest(SentinelRequest request, SentinelCallBudget budget, LocalDateTime runStartedAt, LocalDateTime syncedAt,
        RunTally tally) {
        Converted converted;
        try {
            converted = fetchAndConvert(request, budget);
        } catch (SentinelImportException exception) {
            return onSourceFailure(request, exception, runStartedAt, syncedAt, tally);
        }

        SentinelFetch<?> fetch = converted.fetch();
        OfficialSnapshotDraft draft = new OfficialSnapshotDraft(OfficialSource.KDCA_SENTINEL, request.program().getOfficialProgram(),
            request.requestKey(), IngestChannel.PORTAL_JSON, fetch.contentSha256(), fetch.byteLength(), fetch.rawRowCount(), runStartedAt);
        try {
            OfficialIngestResult result = officialIngestProcessor.ingest(draft, converted.records(), syncedAt);
            tally.importedRequests++;
            tally.importedRows += result.importedCount();
            tally.nullValues += fetch.nullValueCount();
            tally.pending += fetch.pendingCount();
            return true;
        } catch (OfficialIngestException exception) {
            // 검증 실패(원천 불일치 · 자연키 중복)는 그 요청만의 문제다. FAILED 이력은 프로세서가 남겼다.
            // 그 이력 쓰기마저 실패했으면 프로세서가 suppressed 로 붙여 둔다 — DB 문제라 이후 쓰기도 실패하므로 멈춘다.
            tally.failedKeys.add(request.requestKey());
            boolean recordFailed = exception.getSuppressed().length > 0;
            log.warn("sentinelImportJob request failed. requestKey={} errorCode={} aborting={} message={}",
                request.requestKey(), exception.getErrorCode().getCode(), recordFailed, exception.getMessage());
            if (recordFailed) {
                tally.abort(OfficialIngestErrorCode.WRITE_FAILED.getCode(), exception);
                return false;
            }
            return true;
        } catch (RuntimeException exception) {
            // 검증을 지난 뒤의 예외는 쓰기 실패다 (프로세서가 롤백하고 FAILED 이력을 남긴 뒤 원래 예외를 다시 던진다). DB 문제면 이후 쓰기도 실패한다.
            tally.failedKeys.add(request.requestKey());
            log.error("sentinelImportJob write failed, aborting run. requestKey={} errorCode={}",
                request.requestKey(), OfficialIngestErrorCode.WRITE_FAILED.getCode(), exception);
            tally.abort(OfficialIngestErrorCode.WRITE_FAILED.getCode(), exception);
            return false;
        }
    }

    /** 원천 호출 · 응답 해석 · 행 변환. 실패는 전부 {@link SentinelImportException} 이다. */
    private Converted fetchAndConvert(SentinelRequest request, SentinelCallBudget budget) {
        if (request.program().isPathogenWeekly()) {
            SentinelFetch<SentinelPathogenRow> fetch = sentinelSourcePort.fetchPathogens(request, budget);
            return new Converted(fetch, sentinelRecordProcessor.pathogenRecords(request, fetch.rows()));
        }
        SentinelFetch<SentinelIliRow> fetch = sentinelSourcePort.fetchInfluenza(request, budget);
        return new Converted(fetch, sentinelRecordProcessor.influenzaRecords(request, fetch.rows()));
    }

    /**
     * 원천 호출 · 변환 실패를 본문 없는 FAILED 이력(sha null · 0바이트 · 0행)으로 남긴다.
     *
     * @return 다음 요청으로 계속할지
     */
    private boolean onSourceFailure(SentinelRequest request, SentinelImportException exception, LocalDateTime runStartedAt, LocalDateTime syncedAt,
        RunTally tally) {
        tally.failedKeys.add(request.requestKey());
        SentinelImportErrorCode errorCode = exception.getErrorCode();
        boolean aborting = ABORTING_SOURCE_ERRORS.contains(errorCode);
        // 메시지는 원천이 준 짧은 값 · 사유뿐이다 — 쿠키 · URL · 응답 본문은 원천 어댑터가 싣지 않는다.
        log.warn("sentinelImportJob request failed. requestKey={} errorCode={} aborting={} message={}",
            request.requestKey(), errorCode.getCode(), aborting, exception.getMessage());

        OfficialSnapshotDraft draft = new OfficialSnapshotDraft(OfficialSource.KDCA_SENTINEL, request.program().getOfficialProgram(),
            request.requestKey(), IngestChannel.PORTAL_JSON, null, 0, 0, runStartedAt);
        try {
            officialIngestProcessor.recordFailure(draft, errorCode.getCode(), syncedAt);
        } catch (RuntimeException recordException) {
            // FAILED 이력조차 못 쓰면 DB 문제다. 이후 요청을 받아도 쓰지 못하므로 멈춘다.
            recordException.addSuppressed(exception);
            log.error("sentinelImportJob failure could not be recorded, aborting run. requestKey={} errorCode={}",
                request.requestKey(), errorCode.getCode(), recordException);
            tally.abort(OfficialIngestErrorCode.WRITE_FAILED.getCode(), recordException);
            return false;
        }

        if (aborting) {
            tally.abort(errorCode.getCode(), exception);
            return false;
        }
        return true;
    }

    private SentinelImportException runFailed(SentinelImportPlan plan, RunTally tally, SentinelCallBudget budget) {
        int notAttempted = plan.size() - tally.attempted;
        boolean aborted = tally.abortedBy != null;
        String abortedBy = aborted ? tally.abortedBy : NO_ABORT;
        List<String> failedKeys = tally.failedKeys.stream().limit(FAILED_KEYS_MESSAGE_LIMIT).toList();
        log.warn("sentinelImportJob run failed. planned={} imported={} failed={} notAttempted={} aborted={} abortedBy={} calls={} nullValues={} pending={}",
            plan.size(), tally.importedRequests, tally.failedKeys.size(), notAttempted, aborted, abortedBy, budget.used(), tally.nullValues,
            tally.pending);

        Object[] args = {plan.size(), tally.importedRequests, tally.failedKeys.size(), notAttempted, aborted, abortedBy, failedKeys};
        // 멈춘 원인은 cause 로 붙여 배치 메타데이터의 EXIT_MESSAGE 에 함께 남긴다. 요청별 실패 원인은 FAILED 이력과 WARN 로그에 있다.
        return aborted
            ? new SentinelImportException(SentinelImportErrorCode.RUN_FAILED, tally.abortCause, args)
            : new SentinelImportException(SentinelImportErrorCode.RUN_FAILED, args);
    }

    private record Converted(SentinelFetch<?> fetch, List<OfficialRecord> records) {

    }

    /** 실행 한 번의 집계. 한 스레드에서만 쓴다. */
    private static final class RunTally {

        private final List<String> failedKeys = new ArrayList<>();
        private int attempted;
        private int importedRequests;
        private int importedRows;
        private int nullValues;
        private int pending;
        private String abortedBy;
        private RuntimeException abortCause;

        private void abort(String errorCode, RuntimeException cause) {
            this.abortedBy = errorCode;
            this.abortCause = cause;
        }
    }
}
