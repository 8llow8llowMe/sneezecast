package com.sneezecast.domainlayer.member.domain.model;

import com.sneezecast.domainlayer.member.domain.enums.PurgeReason;
import java.time.Duration;
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

    /**
     * 이 시각에 시작한 파기 호출이 성공하면 요청이 끝나는지. 호출 시작이 {@code requestedAt + settleWindow} 와 같거나 그 뒤여야 한다.
     *
     * <p>{@code settleWindow} 는 access token 수명 + 여유다. 철회 직전에 발급된 다른 기기의 access token 에는 {@code report:write} 가 그만큼
     * 남는다 — 그 전에 시작한 호출은 그 토큰으로 뒤늦게 들어올 보고를 지운다고 보장하지 못한다. 기준은 성공 시각이 아니라 <b>호출 시작 시각</b>이다:
     * 시작 뒤에 커밋된 보고는 그 호출의 삭제에 걸리지 않을 수 있다.
     *
     * @param callStartedAt 파기 호출을 보내기 직전 시각 ({@code requestedAt} 과 같은 기준 — JVM 기본 시간대 {@code LocalDateTime.now()})
     * @param settleWindow  access token 수명 + 완료 여유
     */
    public boolean isCompletedBy(LocalDateTime callStartedAt, Duration settleWindow) {
        return !callStartedAt.isBefore(requestedAt.plus(settleWindow));
    }

    /**
     * 회원 ID 를 싣지 않는다. record 기본 {@code toString} 은 모든 필드를 내보내, 로그 한 줄로 "이 회원이 건강정보 동의를 철회했다" 가 남는다
     * (surveillance {@code WeeklyReport.toString} 과 같은 이유).
     */
    @Override
    public String toString() {
        return "ReportPurgeRequest[id=" + id + ", reason=" + reason + ", attemptCount=" + attemptCount + ", completed=" + (completedAt != null) + "]";
    }
}
