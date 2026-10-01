package com.sneezecast.domainlayer.districtimport.application.port.out;

import com.sneezecast.domainlayer.districtimport.domain.model.ImportedDistrict;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;

/**
 * district 테이블 대량 쓰기와 그 판단에 필요한 읽기. batch 는 JPA 를 쓰지 않으므로 읽기도 JDBC 다.
 */
public interface DistrictBulkPort {

    /**
     * 코드 기준 멱등 upsert. 신규는 {@code valid_from_year = last_seen_year = year} 로 넣고, 기존 행은 이름 · 시도 · 시군구 · {@code last_seen_year}
     * 를 갱신하고 {@code valid_to_year} 를 null(현행)로 되돌린다. {@code valid_from_year} 는 갱신하지 않는다 — "처음 본 연도" 다.
     * {@code synced_at} · {@code created_at} · {@code updated_at} 은 모두 {@code syncedAt} 이다 (DB 세션 시간대가 아니라 JVM 시각).
     *
     * @return upsert 로 보낸 행 수
     */
    int upsertAll(List<ImportedDistrict> districts, int year, LocalDateTime syncedAt);

    /**
     * 현행(valid_to_year IS NULL)인 행만 폐지한다. 이미 폐지된 행의 폐지 연도는 바꾸지 않는다. {@code last_seen_year} 는 건드리지 않는다.
     *
     * @return 폐지한 행 수
     */
    int retire(Collection<String> codes, int validToYear, LocalDateTime updatedAt);

    /** 현행 코드 전체. */
    Set<String> findActiveCodes();

    /**
     * 마지막으로 적재된 SGIS 기준 연도 = {@code MAX(last_seen_year)}. 테이블이 비어 있으면 empty.
     *
     * <p>{@code valid_from_year} · {@code valid_to_year} 로는 셈할 수 없다 — 폐지만 있던 해는 {@code valid_from_year} 에 남지 않고, 폐지됐다
     * 재등장한 코드는 {@code valid_to_year} 를 지워 그 폐지 기록이 사라진다. 그래서 적재마다 갱신되는 컬럼을 따로 둔다.
     */
    OptionalInt findLastLoadedYear();
}
