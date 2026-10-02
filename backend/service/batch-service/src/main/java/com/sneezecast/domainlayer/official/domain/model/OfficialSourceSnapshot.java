package com.sneezecast.domainlayer.official.domain.model;

import com.sneezecast.domainlayer.official.domain.enums.IngestStatus;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * INSERT 할 {@code official_source_snapshot} 한 행 (entity-design §3-3). 수정하지 않고 쌓기만 한다.
 *
 * <p>IMPORTED 는 {@code error_code} 가 없고, FAILED 는 {@code error_code} 가 있고 {@code imported_count} 가 0 이다 — FAILED 행은 원천 데이터를
 * 쓰지 않는다.
 *
 * @param id            PK (Snowflake)
 * @param importedCount upsert 한 행 수
 * @param errorCode     실패 코드 (VARCHAR(50)). IMPORTED 면 null
 * @param createdAt     {@code created_at} · {@code updated_at} 에 함께 쓰는 시각
 */
public record OfficialSourceSnapshot(
    long id,
    OfficialSnapshotDraft draft,
    IngestStatus status,
    int importedCount,
    String errorCode,
    LocalDateTime createdAt
) {

    private static final int ERROR_CODE_MAX_LENGTH = 50;

    public OfficialSourceSnapshot {
        Objects.requireNonNull(draft, "draft");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        if (importedCount < 0) {
            throw new IllegalArgumentException("importedCount must not be negative. importedCount=" + importedCount);
        }
        if (status == IngestStatus.IMPORTED && errorCode != null) {
            throw new IllegalArgumentException("IMPORTED snapshot must not have errorCode. errorCode=" + errorCode);
        }
        if (status == IngestStatus.FAILED) {
            if (errorCode == null || errorCode.isBlank() || errorCode.length() > ERROR_CODE_MAX_LENGTH) {
                throw new IllegalArgumentException("FAILED snapshot needs errorCode of 1..%d characters. errorCode=%s"
                    .formatted(ERROR_CODE_MAX_LENGTH, errorCode));
            }
            if (importedCount != 0) {
                throw new IllegalArgumentException("FAILED snapshot must have importedCount 0. importedCount=" + importedCount);
            }
        }
    }

    public static OfficialSourceSnapshot imported(long id, OfficialSnapshotDraft draft, int importedCount, LocalDateTime createdAt) {
        return new OfficialSourceSnapshot(id, draft, IngestStatus.IMPORTED, importedCount, null, createdAt);
    }

    public static OfficialSourceSnapshot failed(long id, OfficialSnapshotDraft draft, String errorCode, LocalDateTime createdAt) {
        return new OfficialSourceSnapshot(id, draft, IngestStatus.FAILED, 0, errorCode, createdAt);
    }
}
