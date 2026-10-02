package com.sneezecast.domainlayer.official.adapter.out.persistence;

import com.sneezecast.domainlayer.official.application.port.out.OfficialSurveillanceBulkPort;
import com.sneezecast.domainlayer.official.domain.model.IdentifiedOfficialRecord;
import com.sneezecast.domainlayer.official.domain.model.OfficialRecord;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * official_surveillance 대량 upsert.
 *
 * <p>테이블 구조의 정본은 surveillance-service 의 {@code OfficialSurveillanceEntity}(JPA)다. batch 는 JDBC 로 직접 쓰므로 컬럼이 바뀌면 양쪽을 같이
 * 고친다 (테스트 DDL {@code official/official-schema.sql} 포함).
 */
@Component
@RequiredArgsConstructor
public class JdbcOfficialSurveillanceBulkAdapter implements OfficialSurveillanceBulkPort {

    static final int BATCH_SIZE = 500;

    /**
     * 키는 자연키 UK 다. PK id 는 Snowflake 라 매번 새 값이므로 기존 행이면 UK 에 걸린다.
     * <ul>
     *   <li>{@code id} · {@code created_at} 은 UPDATE 절에 없다 — 기존 행 id 를 유지하고, 새로 만든 Snowflake 값은 버려진다.</li>
     *   <li>자연키 10 컬럼은 같은 값이라 갱신하지 않는다. 표시용 이름(감염병 · 지역)과 분류 · 기간 · 값 · 쓴 실행 · 시각은 원천을 따라
     *       갱신한다 — 원천이 이름을 고치면(예: 시도 통합) 그대로 반영된다.</li>
     *   <li>시각 컬럼은 전부 JVM 의 {@code syncedAt} 을 바인딩한다 — {@code NOW()} 는 DB 세션 시간대를 따라 surveillance {@code BaseEntity}(JVM 시각)와
     *       어긋날 수 있다.</li>
     * </ul>
     */
    static final String UPSERT_SQL = """
        INSERT INTO official_surveillance (
            id, source, program, disease_key, disease_name, disease_group, metric, age_group, region_level, region_code, region_name,
            period_type, period_year, period_week, period_start, period_end, metric_value, source_snapshot_id, synced_at, created_at, updated_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON DUPLICATE KEY UPDATE
            disease_name = VALUES(disease_name),
            disease_group = VALUES(disease_group),
            region_name = VALUES(region_name),
            period_start = VALUES(period_start),
            period_end = VALUES(period_end),
            metric_value = VALUES(metric_value),
            source_snapshot_id = VALUES(source_snapshot_id),
            synced_at = VALUES(synced_at),
            updated_at = VALUES(updated_at)
        """;

    private final JdbcTemplate jdbcTemplate;

    /**
     * 반환값은 보낸 행 수다. {@code ON DUPLICATE KEY UPDATE} 의 영향 행 수는 드라이버 · 설정마다 다르게 온다 (MySQL 은 갱신 2, 변화 없음 0,
     * rewriteBatchedStatements 면 SUCCESS_NO_INFO) — 신규와 갱신을 그 값으로 가르지 않는다.
     */
    @Override
    public int upsertAll(List<IdentifiedOfficialRecord> rows, long sourceSnapshotId, LocalDateTime syncedAt) {
        if (rows.isEmpty()) {
            return 0;
        }
        Timestamp syncedAtValue = Timestamp.valueOf(syncedAt);
        for (int start = 0; start < rows.size(); start += BATCH_SIZE) {
            List<IdentifiedOfficialRecord> chunk = rows.subList(start, Math.min(start + BATCH_SIZE, rows.size()));
            jdbcTemplate.batchUpdate(UPSERT_SQL, new BatchPreparedStatementSetter() {
                @Override
                public void setValues(PreparedStatement statement, int index) throws SQLException {
                    IdentifiedOfficialRecord row = chunk.get(index);
                    OfficialRecord record = row.record();
                    statement.setLong(1, row.id());
                    statement.setString(2, record.source().name());
                    statement.setString(3, record.program().name());
                    statement.setString(4, record.diseaseKey());
                    statement.setString(5, record.diseaseName());
                    if (record.diseaseGroup() == null) {
                        statement.setNull(6, Types.VARCHAR);
                    } else {
                        statement.setString(6, record.diseaseGroup());
                    }
                    statement.setString(7, record.metric().name());
                    statement.setString(8, record.ageGroup().name());
                    statement.setString(9, record.regionLevel().name());
                    statement.setString(10, record.regionCode());
                    statement.setString(11, record.regionName());
                    statement.setString(12, record.periodType().name());
                    statement.setInt(13, record.periodYear());
                    statement.setInt(14, record.periodWeek());
                    // LocalDate 를 그대로 넘긴다 — java.sql.Date 는 JVM 시간대 자정을 거쳐 드라이버 설정에 따라 하루 밀릴 수 있다.
                    statement.setObject(15, record.periodStart());
                    statement.setObject(16, record.periodEnd());
                    if (record.metricValue() == null) {
                        statement.setNull(17, Types.DECIMAL);
                    } else {
                        statement.setBigDecimal(17, record.metricValue());
                    }
                    statement.setLong(18, sourceSnapshotId);
                    statement.setTimestamp(19, syncedAtValue);
                    statement.setTimestamp(20, syncedAtValue);
                    statement.setTimestamp(21, syncedAtValue);
                }

                @Override
                public int getBatchSize() {
                    return chunk.size();
                }
            });
        }
        return rows.size();
    }
}
