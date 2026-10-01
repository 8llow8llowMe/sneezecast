package com.sneezecast.domainlayer.districtimport.adapter.out.persistence;

import com.sneezecast.domainlayer.districtimport.application.port.out.DistrictBulkPort;
import com.sneezecast.domainlayer.districtimport.domain.model.ImportedDistrict;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * district 대량 upsert · 폐지.
 *
 * <p>테이블 구조의 정본은 surveillance-service 의 {@code DistrictEntity}(JPA)다. batch 는 JDBC 로 직접 쓰므로 컬럼이 바뀌면 양쪽을 같이 고친다
 * (테스트 DDL {@code districtimport/district-schema.sql} 포함).
 */
@Component
@RequiredArgsConstructor
public class JdbcDistrictBulkAdapter implements DistrictBulkPort {

    static final int BATCH_SIZE = 500;

    /**
     * 키는 코드다 (PK id 도 코드에서 결정적으로 만들어 둘 중 무엇에 걸려도 같은 행이다).
     * <ul>
     *   <li>{@code valid_from_year} 는 UPDATE 절에 없다 — 처음 본 연도를 유지한다.</li>
     *   <li>{@code valid_to_year = NULL} — 폐지됐던 코드가 새 스냅샷에 다시 나오면 현행으로 되돌린다.</li>
     *   <li>{@code last_seen_year} — 이번 적재 연도. 연도 역행 판정({@link #findLastLoadedYear()})의 근거다.</li>
     *   <li>시각 컬럼은 전부 JVM 의 {@code syncedAt} 을 바인딩한다 — {@code NOW()} 는 DB 세션 시간대를 따라 surveillance {@code BaseEntity}(JVM 시각)와
     *       어긋날 수 있다.</li>
     * </ul>
     */
    static final String UPSERT_SQL = """
        INSERT INTO district (
            id, code, name, sido_code, sido_name, sigungu_code, sigungu_name, valid_from_year, valid_to_year, last_seen_year, synced_at, created_at, updated_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, ?, ?)
        ON DUPLICATE KEY UPDATE
            name = VALUES(name),
            sido_code = VALUES(sido_code),
            sido_name = VALUES(sido_name),
            sigungu_code = VALUES(sigungu_code),
            sigungu_name = VALUES(sigungu_name),
            valid_to_year = NULL,
            last_seen_year = VALUES(last_seen_year),
            synced_at = VALUES(synced_at),
            updated_at = VALUES(updated_at)
        """;

    /** 이미 폐지된 행의 폐지 연도는 덮어쓰지 않는다. {@code last_seen_year} 는 건드리지 않는다. {@code IN} 자리는 청크 크기만큼 채운다. */
    private static final String RETIRE_SQL_PREFIX = "UPDATE district SET valid_to_year = ?, updated_at = ? WHERE valid_to_year IS NULL AND code IN (";

    private static final String FIND_ACTIVE_CODES_SQL = "SELECT code FROM district WHERE valid_to_year IS NULL";

    private static final String FIND_LAST_LOADED_YEAR_SQL = "SELECT MAX(last_seen_year) FROM district";

    private final JdbcTemplate jdbcTemplate;

    /**
     * 반환값은 보낸 행 수다. {@code ON DUPLICATE KEY UPDATE} 의 영향 행 수는 드라이버 · 설정마다 다르게 온다 (MySQL 은 갱신 2, 변화 없음 0,
     * rewriteBatchedStatements 면 SUCCESS_NO_INFO) — 신규와 갱신을 그 값으로 가르지 않는다.
     */
    @Override
    public int upsertAll(List<ImportedDistrict> districts, int year, LocalDateTime syncedAt) {
        if (districts.isEmpty()) {
            return 0;
        }
        Timestamp syncedAtValue = Timestamp.valueOf(syncedAt);
        for (int start = 0; start < districts.size(); start += BATCH_SIZE) {
            List<ImportedDistrict> chunk = districts.subList(start, Math.min(start + BATCH_SIZE, districts.size()));
            jdbcTemplate.batchUpdate(UPSERT_SQL, new BatchPreparedStatementSetter() {
                @Override
                public void setValues(PreparedStatement statement, int index) throws SQLException {
                    ImportedDistrict district = chunk.get(index);
                    statement.setLong(1, district.id());
                    statement.setString(2, district.code());
                    statement.setString(3, district.name());
                    statement.setString(4, district.sidoCode());
                    statement.setString(5, district.sidoName());
                    statement.setString(6, district.sigunguCode());
                    statement.setString(7, district.sigunguName());
                    statement.setInt(8, year);
                    statement.setInt(9, year);
                    statement.setTimestamp(10, syncedAtValue);
                    statement.setTimestamp(11, syncedAtValue);
                    statement.setTimestamp(12, syncedAtValue);
                }

                @Override
                public int getBatchSize() {
                    return chunk.size();
                }
            });
        }
        return districts.size();
    }

    @Override
    public int retire(Collection<String> codes, int validToYear, LocalDateTime updatedAt) {
        if (codes.isEmpty()) {
            return 0;
        }
        List<String> codeList = new ArrayList<>(codes);
        int retired = 0;
        // 코드 수만큼 단건 UPDATE 를 돌리지 않고 IN 절 청크로 묶는다 (coding-conventions §8-5).
        for (int start = 0; start < codeList.size(); start += BATCH_SIZE) {
            List<String> chunk = codeList.subList(start, Math.min(start + BATCH_SIZE, codeList.size()));
            String sql = RETIRE_SQL_PREFIX + chunk.stream().map(code -> "?").collect(Collectors.joining(", ")) + ")";
            Object[] args = new Object[chunk.size() + 2];
            args[0] = validToYear;
            args[1] = Timestamp.valueOf(updatedAt);
            for (int index = 0; index < chunk.size(); index++) {
                args[index + 2] = chunk.get(index);
            }
            retired += jdbcTemplate.update(sql, args);
        }
        return retired;
    }

    @Override
    public Set<String> findActiveCodes() {
        return new HashSet<>(jdbcTemplate.queryForList(FIND_ACTIVE_CODES_SQL, String.class));
    }

    @Override
    public OptionalInt findLastLoadedYear() {
        Integer lastLoadedYear = jdbcTemplate.queryForObject(FIND_LAST_LOADED_YEAR_SQL, Integer.class);
        return lastLoadedYear == null ? OptionalInt.empty() : OptionalInt.of(lastLoadedYear);
    }
}
