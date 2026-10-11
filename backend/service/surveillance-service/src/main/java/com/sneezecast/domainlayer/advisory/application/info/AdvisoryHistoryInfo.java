package com.sneezecast.domainlayer.advisory.application.info;

import com.sneezecast.domainlayer.advisory.domain.enums.AdvisoryStatus;
import com.sneezecast.domainlayer.advisory.domain.model.AdvisoryHistory;
import java.time.LocalDateTime;

/**
 * 안내 이력 한 행 — 운영자 발행 이력 화면(#220)의 {@code timeline} 한 칸이 된다.
 *
 * @param fromStatus 이전 상태. 생성이면 null
 * @param operatorId 처리한 운영자 (auth member.id). 표시 이름은 surveillance 에 없다
 * @param memo       수정 · 철회 사유. 없으면 null
 * @param createdAt  전이 시각 (저장 기준 지역 시각)
 */
public record AdvisoryHistoryInfo(
    long id,
    long advisoryId,
    AdvisoryStatus fromStatus,
    AdvisoryStatus toStatus,
    String title,
    String body,
    long operatorId,
    String aiModel,
    String promptVersion,
    String memo,
    LocalDateTime createdAt
) {

    public static AdvisoryHistoryInfo from(AdvisoryHistory history) {
        return new AdvisoryHistoryInfo(history.id(), history.advisoryId(), history.fromStatus(), history.toStatus(), history.title(), history.body(),
            history.operatorId(), history.aiModel(), history.promptVersion(), history.memo(), history.createdAt());
    }
}
