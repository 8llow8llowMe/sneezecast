package com.sneezecast.domainlayer.official.adapter.out.persistence;

import com.sneezecast.domainlayer.official.application.port.out.OfficialSourceSnapshotBulkPort;
import com.sneezecast.domainlayer.official.domain.model.OfficialSnapshotDraft;
import com.sneezecast.domainlayer.official.domain.model.OfficialSourceSnapshot;
import java.sql.Timestamp;
import java.sql.Types;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * official_source_snapshot 쓰기. 수정하지 않고 쌓기만 한다 (INSERT 만).
 *
 * <p>테이블 구조의 정본은 surveillance-service 의 {@code OfficialSourceSnapshotEntity}(JPA)다. 컬럼이 바뀌면 양쪽을 같이 고친다
 * (테스트 DDL {@code official/official-schema.sql} 포함).
 */
@Component
@RequiredArgsConstructor
public class JdbcOfficialSourceSnapshotBulkAdapter implements OfficialSourceSnapshotBulkPort {

    /** 시각 컬럼은 JVM 시각을 바인딩한다 ({@link JdbcOfficialSurveillanceBulkAdapter#UPSERT_SQL} 과 같은 이유). */
    static final String INSERT_SQL = """
        INSERT INTO official_source_snapshot (
            id, source, program, request_key, channel, content_sha256, byte_length, row_count, imported_count, status, error_code,
            run_started_at, created_at, updated_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;

    private static final int[] INSERT_TYPES = {
        Types.BIGINT, Types.VARCHAR, Types.VARCHAR, Types.VARCHAR, Types.VARCHAR, Types.CHAR, Types.INTEGER, Types.INTEGER, Types.INTEGER,
        Types.VARCHAR, Types.VARCHAR, Types.TIMESTAMP, Types.TIMESTAMP, Types.TIMESTAMP
    };

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void insert(OfficialSourceSnapshot snapshot) {
        OfficialSnapshotDraft draft = snapshot.draft();
        Timestamp createdAt = Timestamp.valueOf(snapshot.createdAt());
        jdbcTemplate.update(INSERT_SQL, new Object[] {
            snapshot.id(),
            draft.source().name(),
            draft.program().name(),
            draft.requestKey(),
            draft.channel().name(),
            draft.contentSha256(),
            draft.byteLength(),
            draft.rowCount(),
            snapshot.importedCount(),
            snapshot.status().name(),
            snapshot.errorCode(),
            Timestamp.valueOf(draft.runStartedAt()),
            createdAt,
            createdAt
        }, INSERT_TYPES);
    }
}
