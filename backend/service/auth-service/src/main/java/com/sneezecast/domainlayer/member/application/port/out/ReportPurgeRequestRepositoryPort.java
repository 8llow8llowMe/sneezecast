package com.sneezecast.domainlayer.member.application.port.out;

import com.sneezecast.domainlayer.member.domain.model.ReportPurgeRequest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ReportPurgeRequestRepositoryPort {

    void save(ReportPurgeRequest request);

    /** 회원에게 완료되지 않은({@code completed_at is null}) 파기 요청이 하나라도 있는지. */
    boolean existsIncompleteByMemberId(long memberId);

    Optional<ReportPurgeRequest> findById(long id);

    /**
     * 이번 회차에 부를 요청. 미완료이고, 아직 한 번도 성공하지 않았거나(1차) 요청 시각이 {@code secondCallDueAt} 과 같거나 이전인(2차 — 대기
     * 시간이 지났다) 것을 요청 시각 · ID 순으로 {@code limit} 건까지.
     */
    List<ReportPurgeRequest> findDue(LocalDateTime secondCallDueAt, int limit);

    /**
     * 파기 성공을 원자 갱신으로 남긴다 — 시도 횟수 + 1, 첫 성공 시각은 처음만, {@code completedAt} 이 null 이 아니면 완료. 이미 완료된 행은 건드리지
     * 않는다. 호출자 트랜잭션이 있어야 한다.
     *
     * @return 갱신한 행 수 (0 또는 1)
     */
    int recordSuccess(long id, LocalDateTime succeededAt, LocalDateTime completedAt);

    /**
     * 파기 실패를 원자 갱신으로 남긴다 — 시도 횟수 + 1, 마지막 실패 사유(200자에서 자른다). 이미 완료된 행은 건드리지 않는다. 호출자 트랜잭션이
     * 있어야 한다.
     *
     * @return 갱신한 행 수 (0 또는 1)
     */
    int recordFailure(long id, String lastError, LocalDateTime failedAt);

    /**
     * 완료 시각이 {@code threshold} 보다 앞선 완료 행을 지운다. 미완료 행은 지우지 않는다. 호출자 트랜잭션이 있어야 한다.
     *
     * @return 지운 행 수
     */
    int deleteCompletedBefore(LocalDateTime threshold);
}
