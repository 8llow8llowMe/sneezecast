package com.sneezecast.domainlayer.official.application.model;

/**
 * @param snapshotId    이번 실행의 IMPORTED 수집 기록 id (official_source_snapshot.id)
 * @param importedCount upsert 로 보낸 행 수 (신규 + 갱신)
 */
public record OfficialIngestResult(long snapshotId, int importedCount) {

}
