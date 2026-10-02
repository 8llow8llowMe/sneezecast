package com.sneezecast.domainlayer.official.application.port.out;

import com.sneezecast.domainlayer.official.domain.model.IdentifiedOfficialRecord;
import java.time.LocalDateTime;
import java.util.List;

/**
 * official_surveillance 대량 쓰기. batch 는 JPA 를 쓰지 않으므로 JDBC 다.
 */
public interface OfficialSurveillanceBulkPort {

    /**
     * 자연키(UK {@code uk_official_surveillance_natural_key}) 기준 멱등 upsert. 신규는 {@code rows} 의 id 로 넣고, 기존 행은
     * 표시 이름 · 분류 · 기간 · 값 · {@code source_snapshot_id} · {@code synced_at} · {@code updated_at} 만 갱신한다.
     * <b>id · created_at 은 갱신하지 않는다</b> — 재적재해도 기존 행 id 가 남는다.
     * {@code synced_at} · {@code created_at} · {@code updated_at} 은 모두 {@code syncedAt} 이다 (DB 세션 시간대가 아니라 JVM 시각).
     *
     * <p>{@code rows} 안에 같은 자연키가 둘 있으면 조용히 하나로 접힌다 — 호출 전에 걸러야 한다.
     *
     * @param sourceSnapshotId 이 값을 쓰는 실행 (official_source_snapshot.id)
     * @return upsert 로 보낸 행 수
     */
    int upsertAll(List<IdentifiedOfficialRecord> rows, long sourceSnapshotId, LocalDateTime syncedAt);
}
