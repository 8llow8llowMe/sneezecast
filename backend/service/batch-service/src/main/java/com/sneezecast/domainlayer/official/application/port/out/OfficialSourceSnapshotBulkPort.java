package com.sneezecast.domainlayer.official.application.port.out;

import com.sneezecast.domainlayer.official.domain.model.OfficialSourceSnapshot;

/**
 * official_source_snapshot 쓰기. 수정하지 않고 쌓기만 하므로 INSERT 하나다.
 */
public interface OfficialSourceSnapshotBulkPort {

    /** {@code created_at} · {@code updated_at} 은 모두 {@code snapshot.createdAt()} 이다. */
    void insert(OfficialSourceSnapshot snapshot);
}
