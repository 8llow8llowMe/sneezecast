package com.sneezecast.domainlayer.official.application.service.processor;

import com.sneezecast.domainlayer.official.application.exception.OfficialIngestErrorCode;
import com.sneezecast.domainlayer.official.application.exception.OfficialIngestException;
import com.sneezecast.domainlayer.official.application.model.OfficialIngestResult;
import com.sneezecast.domainlayer.official.application.port.out.OfficialSourceSnapshotBulkPort;
import com.sneezecast.domainlayer.official.application.port.out.OfficialSurveillanceBulkPort;
import com.sneezecast.domainlayer.official.domain.model.IdentifiedOfficialRecord;
import com.sneezecast.domainlayer.official.domain.model.OfficialRecord;
import com.sneezecast.domainlayer.official.domain.model.OfficialSnapshotDraft;
import com.sneezecast.domainlayer.official.domain.model.OfficialSourceSnapshot;
import com.sneezecast.persistence.util.SnowflakeIdGenerator;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 질병관리청 감시 자료의 <b>유일한 쓰기 경로</b> (entity-design §3-2 · §3-3). 전수신고 · 표본감시 잡이 같은 순서로 쓰게 한다.
 *
 * <p>순서: 쓰기 전 검증 → [쓰기 트랜잭션: IMPORTED 수집 기록 INSERT → 행 upsert({@code source_snapshot_id} = 그 기록)].
 * 둘 중 하나라도 실패하면 둘 다 롤백되고, 별도 트랜잭션으로 FAILED 수집 기록을 남긴 뒤 원래 예외를 다시 던진다 — 기존 값은 그대로다.
 * 검증에서 막혀도 행은 쓰지 않고 FAILED 수집 기록만 남긴다.
 *
 * <p><b>불변식: 원천 요청 하나는 수집 기록 한 행을 남긴다</b> — 성공이면 IMPORTED, 검증 · 쓰기 실패면 FAILED({@code ingest} 가 남긴다),
 * 원천 호출 · 파싱 실패면 FAILED(잡이 {@link #recordFailure} 로 남긴다). FAILED 기록 쓰기마저 실패한 경우만 예외다 — 원래 예외에 suppressed 로
 * 붙고 ERROR 로그가 남는다.
 *
 * <p><b>원천 호출은 여기 오기 전에 끝낸다.</b> 이 클래스는 DB 만 만진다. DB 구간을 primary 매니저의 {@link TransactionTemplate} 으로 감싸는 이유는
 * {@code DistrictImportFacade} 와 같다 — 스텝을 감싼 {@code taskletTransactionManager}(무자원)는 트랜잭션 동기화를 켜므로, 감싸지 않은
 * {@code JdbcTemplate} 호출은 커넥션을 스레드에 묶어 둔 채 스텝이 끝날 때까지 놓지 않는다. 잡이 요청마다 원천을 부르고 여기를 부르므로,
 * 묶이면 다음 원천 호출 내내 커넥션을 쥔다.
 *
 * <p>시각은 호출자가 넘긴다. 수집 기록의 {@code created_at} · {@code updated_at} 과 행의 시각 컬럼이 한 시각을 쓴다 (한 실행 안에서 시각 출처를 하나로).
 */
@Slf4j
@Component
public class OfficialIngestProcessor {

    private final OfficialSurveillanceBulkPort officialSurveillanceBulkPort;
    private final OfficialSourceSnapshotBulkPort officialSourceSnapshotBulkPort;
    private final SnowflakeIdGenerator snowflakeIdGenerator;
    private final TransactionTemplate writeTransaction;
    private final TransactionTemplate failureTransaction;

    /**
     * @param transactionManager primary(DataSource) 매니저. 스텝이 도는 {@code taskletTransactionManager} 는 무자원이라 쓰기를 묶지 못한다
     */
    public OfficialIngestProcessor(
        OfficialSurveillanceBulkPort officialSurveillanceBulkPort,
        OfficialSourceSnapshotBulkPort officialSourceSnapshotBulkPort,
        SnowflakeIdGenerator snowflakeIdGenerator,
        @Qualifier("transactionManager") PlatformTransactionManager transactionManager
    ) {
        this.officialSurveillanceBulkPort = officialSurveillanceBulkPort;
        this.officialSourceSnapshotBulkPort = officialSourceSnapshotBulkPort;
        this.snowflakeIdGenerator = snowflakeIdGenerator;
        this.writeTransaction = new TransactionTemplate(transactionManager);
        // FAILED 기록은 실패한 쓰기와 운명을 같이하면 안 된다. 호출자가 실수로 DB 트랜잭션 안에서 불러도 따로 커밋되게 새 트랜잭션으로 연다.
        this.failureTransaction = new TransactionTemplate(transactionManager);
        this.failureTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * 요청 하나의 결과를 쓴다. {@code records} 가 비어 있어도 IMPORTED 수집 기록(imported_count 0)은 남긴다 — "받았는데 없었다" 도 기록이다.
     *
     * <p>원천 불일치 · 자연키 중복은 행을 쓰지 않고 FAILED 수집 기록({@code error_code} = 그 에러코드의 code)만 남긴 뒤
     * {@link OfficialIngestException} 을 던진다. 쓰기 자체가 실패하면 롤백 뒤 FAILED 수집 기록({@code error_code} =
     * {@link OfficialIngestErrorCode#WRITE_FAILED} 코드)을 남기고 원래 예외를 다시 던진다.
     *
     * @param syncedAt 행의 {@code synced_at} · 수집 기록의 {@code created_at} 으로 쓸 시각
     */
    public OfficialIngestResult ingest(OfficialSnapshotDraft draft, List<OfficialRecord> records, LocalDateTime syncedAt) {
        try {
            validate(draft, records);
        } catch (OfficialIngestException exception) {
            recordFailureQuietly(draft, exception.getErrorCode().getCode(), syncedAt, exception);
            throw exception;
        }

        long snapshotId = snowflakeIdGenerator.generateId();
        List<IdentifiedOfficialRecord> rows = records.stream()
            .map(record -> new IdentifiedOfficialRecord(snowflakeIdGenerator.generateId(), record))
            .toList();
        try {
            writeTransaction.executeWithoutResult(status -> {
                officialSourceSnapshotBulkPort.insert(OfficialSourceSnapshot.imported(snapshotId, draft, rows.size(), syncedAt));
                officialSurveillanceBulkPort.upsertAll(rows, snapshotId, syncedAt);
            });
        } catch (RuntimeException exception) {
            // 원래 예외는 스텝 실패로 배치 메타데이터 · 로그에 남는다. 여기서는 FAILED 기록만 더한다 (recordFailure 가 WARN 로그를 남긴다).
            recordFailureQuietly(draft, OfficialIngestErrorCode.WRITE_FAILED.getCode(), syncedAt, exception);
            throw exception;
        }

        log.info("Official ingest imported. source={} program={} requestKey={} snapshotId={} rowCount={} imported={}",
            draft.source(), draft.program(), draft.requestKey(), snapshotId, draft.rowCount(), rows.size());
        return new OfficialIngestResult(snapshotId, rows.size());
    }

    /**
     * 원천 호출 · 파싱 실패를 FAILED 수집 기록으로 남긴다 (imported_count 0). 원천 데이터는 쓰지 않으므로 기존 값은 그대로다.
     *
     * @param errorCode  실패 코드 (1 ~ 50자, 예: 잡 에러코드의 code)
     * @param recordedAt 수집 기록의 {@code created_at} 으로 쓸 시각
     */
    public void recordFailure(OfficialSnapshotDraft draft, String errorCode, LocalDateTime recordedAt) {
        OfficialSourceSnapshot snapshot = OfficialSourceSnapshot.failed(snowflakeIdGenerator.generateId(), draft, errorCode, recordedAt);
        failureTransaction.executeWithoutResult(status -> officialSourceSnapshotBulkPort.insert(snapshot));
        log.warn("Official ingest failure recorded. source={} program={} requestKey={} snapshotId={} errorCode={}",
            draft.source(), draft.program(), draft.requestKey(), snapshot.id(), errorCode);
    }

    /** FAILED 기록이 실패해도 원래 예외를 가리지 않는다 — suppressed 로 붙이고 ERROR 로그만 남긴다. */
    private void recordFailureQuietly(OfficialSnapshotDraft draft, String errorCode, LocalDateTime syncedAt, RuntimeException cause) {
        try {
            recordFailure(draft, errorCode, syncedAt);
        } catch (RuntimeException recordException) {
            cause.addSuppressed(recordException);
            log.error("Official ingest failure could not be recorded. source={} program={} requestKey={}",
                draft.source(), draft.program(), draft.requestKey(), recordException);
        }
    }

    /**
     * 쓰기 전에 거른다. 같은 배치에 자연키가 둘 있으면 {@code ON DUPLICATE KEY UPDATE} 가 조용히 하나로 접어 어느 값이 남는지 순서에 달린다.
     * 컬럼 길이 · 기간 · 값 자릿수는 {@link OfficialRecord} 생성 때 이미 확인됐다.
     */
    private void validate(OfficialSnapshotDraft draft, List<OfficialRecord> records) {
        Set<OfficialRecord.NaturalKey> seenKeys = new HashSet<>();
        for (OfficialRecord record : records) {
            if (record.source() != draft.source() || record.program() != draft.program()) {
                throw new OfficialIngestException(OfficialIngestErrorCode.SOURCE_MISMATCH, draft.requestKey(), draft.source(), draft.program(),
                    record.source(), record.program());
            }
            if (!seenKeys.add(record.naturalKey())) {
                throw new OfficialIngestException(OfficialIngestErrorCode.DUPLICATE_NATURAL_KEY, draft.requestKey(), record.naturalKey());
            }
        }
    }
}
