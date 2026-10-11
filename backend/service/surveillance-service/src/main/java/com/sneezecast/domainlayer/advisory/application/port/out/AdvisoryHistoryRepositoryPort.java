package com.sneezecast.domainlayer.advisory.application.port.out;

import com.sneezecast.domainlayer.advisory.domain.model.AdvisoryHistory;
import java.util.List;

/**
 * 예방 안내 이력 저장 포트 (entity-design §2-4). 쌓기만 한다 — 고치거나 지우는 메서드를 두지 않는다.
 */
public interface AdvisoryHistoryRepositoryPort {

    /** 이력 한 행을 저장한다. {@code id} 는 호출자가 Snowflake 로 정한다. */
    AdvisoryHistory insert(AdvisoryHistory history);

    /** 안내 하나의 전 이력, 오래된 것부터 ({@code idx_advisory_history_advisory_id}). 없으면 빈 목록이다. */
    List<AdvisoryHistory> findAllByAdvisoryId(long advisoryId);
}
