package com.sneezecast.domainlayer.member.domain.model;

import com.sneezecast.domainlayer.member.domain.enums.PurgeReason;
import java.time.LocalDateTime;
import lombok.Builder;

/**
 * surveillance 원시 보고 파기 요청 한 줄 (entity-design §1-5). 완료될 때까지 스케줄러가 다시 부른다.
 *
 * @param requestedAt   요청 시각 (탈퇴 · 철회 시각)
 * @param firstPurgedAt 첫 파기 성공 시각
 * @param completedAt   완료 시각. null 이면 미완료
 * @param attemptCount  호출 시도 횟수
 * @param lastError     마지막 실패 사유 (예외 코드 · 상태 코드만)
 */
@Builder
public record ReportPurgeRequest(
    long id,
    long memberId,
    PurgeReason reason,
    LocalDateTime requestedAt,
    LocalDateTime firstPurgedAt,
    LocalDateTime completedAt,
    int attemptCount,
    String lastError
) {

}
