package com.sneezecast.domainlayer.member.application.service.processor;

import com.sneezecast.domainlayer.member.application.port.out.ReportPurgeRequestRepositoryPort;
import com.sneezecast.domainlayer.member.domain.model.ReportPurgeRequest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.OptionalInt;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 원시 보고 파기 요청의 DB 구간 — 대상 조회 · 결과 기록 · 완료 행 정리 (entity-design §1-5). 요청을 남기는 쪽은 {@link ReportPurgeRequestProcessor} 다.
 *
 * <p><b>메서드마다 트랜잭션을 연다.</b> 파사드는 그 사이에 surveillance 를 부르므로 트랜잭션을 걸지 않는다 (architecture-guide §3-1) — 원격 응답을
 * 기다리는 동안 커넥션 · 행 잠금을 쥐지 않게 조회와 기록을 따로 끊는다. 기록은 읽고-고치고-쓰기가 아니라 원자 갱신이라, 조회와 기록 사이에 다른
 * 인스턴스가 같은 행을 처리해도 시도 횟수가 사라지거나 완료가 되돌려지지 않는다.
 */
@Service
@RequiredArgsConstructor
public class ReportPurgeExecutionProcessor {

    /** 파기 성공 기록의 결과. */
    public enum SuccessRecord {
        /** 이번 호출로 완료됐다. */
        COMPLETED,
        /** 파기는 됐지만 대기 시간 전에 시작한 호출이라 완료가 아니다 — 대기 뒤 2차 호출이 남았다. */
        AWAITING_SECOND_CALL,
        /** 그 사이 다른 실행이 먼저 완료했다. 아무것도 바꾸지 않았다. */
        ALREADY_COMPLETED
    }

    private final ReportPurgeRequestRepositoryPort reportPurgeRequestRepositoryPort;

    /**
     * 이번 회차에 부를 요청 — 1차 파기 전인 것은 바로, 이미 한 번 성공한 것은 {@code requestedAt + settleWindow} 가 지난 뒤에만. 그 사이에 부른
     * 호출은 완료 판정({@link ReportPurgeRequest#isCompletedBy})을 통과할 수 없어 쓸모가 없다.
     */
    @Transactional(readOnly = true)
    public List<ReportPurgeRequest> findDue(LocalDateTime now, Duration settleWindow, int limit) {
        return reportPurgeRequestRepositoryPort.findDue(now.minus(settleWindow), limit);
    }

    /**
     * 파기 성공을 남긴다. 호출 시작 시각이 대기 시간을 넘겼으면 성공 시각으로 완료 처리한다.
     *
     * @param callStartedAt 호출을 보내기 직전 시각 — 완료 판정 기준
     * @param succeededAt   응답을 받은 시각 — 첫 성공 · 완료 시각으로 남긴다
     */
    @Transactional
    public SuccessRecord recordSuccess(ReportPurgeRequest request, LocalDateTime callStartedAt, LocalDateTime succeededAt, Duration settleWindow) {
        boolean completes = request.isCompletedBy(callStartedAt, settleWindow);
        int updated = reportPurgeRequestRepositoryPort.recordSuccess(request.id(), succeededAt, completes ? succeededAt : null);
        if (updated == 0) {
            return SuccessRecord.ALREADY_COMPLETED;
        }
        return completes ? SuccessRecord.COMPLETED : SuccessRecord.AWAITING_SECOND_CALL;
    }

    /**
     * 파기 실패를 남기고, 남긴 뒤의 시도 횟수를 돌려준다(경보 판정용). 같은 트랜잭션에서 다시 읽으므로 다른 인스턴스의 기록까지 반영된 값이다.
     *
     * @return 기록 뒤 시도 횟수. 그 사이 다른 실행이 완료해 기록하지 않았으면 empty
     */
    @Transactional
    public OptionalInt recordFailure(long requestId, String lastError, LocalDateTime failedAt) {
        if (reportPurgeRequestRepositoryPort.recordFailure(requestId, lastError, failedAt) == 0) {
            return OptionalInt.empty();
        }
        return reportPurgeRequestRepositoryPort.findById(requestId)
            .map(request -> OptionalInt.of(request.attemptCount()))
            .orElseGet(OptionalInt::empty);
    }

    /**
     * 완료 시각이 {@code threshold} 보다 앞선 완료 행을 지운다. 미완료 행은 기간과 상관없이 남긴다 (포기 상태가 없다).
     *
     * @return 지운 행 수
     */
    @Transactional
    public int deleteCompletedBefore(LocalDateTime threshold) {
        return reportPurgeRequestRepositoryPort.deleteCompletedBefore(threshold);
    }
}
