package com.sneezecast.domainlayer.member.application.service;

import com.sneezecast.domainlayer.member.application.model.ReportPurgeCallResult;
import com.sneezecast.domainlayer.member.application.model.ReportPurgeRunResult;
import com.sneezecast.domainlayer.member.application.port.in.ReportPurgeUseCase;
import com.sneezecast.domainlayer.member.application.port.out.ReportPurgeCommandPort;
import com.sneezecast.domainlayer.member.application.service.processor.ReportPurgeExecutionProcessor;
import com.sneezecast.domainlayer.member.application.service.processor.ReportPurgeExecutionProcessor.SuccessRecord;
import com.sneezecast.domainlayer.member.domain.model.ReportPurgeRequest;
import com.sneezecast.global.properties.ReportPurgeProperties;
import com.sneezecast.security.auth.jwt.JwtAuthProperties;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.OptionalInt;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 원시 보고 파기 회차를 돌린다 (entity-design §1-5).
 *
 * <p><b>트랜잭션을 걸지 않는다.</b> 항목마다 surveillance 를 부르므로(외부 I/O) 조회 · 기록만 {@link ReportPurgeExecutionProcessor} 의 메서드
 * 단위 트랜잭션으로 좁힌다 (architecture-guide §3-1).
 *
 * <p>시각은 {@code LocalDateTime.now()}(JVM 기본 시간대)로 잡는다 — {@code requested_at} 을 남기는 {@code MemberConsentProcessor} 와 같은
 * 기준이어야 대기 시간 비교가 어긋나지 않는다.
 *
 * <p><b>인스턴스 하나 전제다.</b> 원자 갱신은 기록(시도 횟수 · 완료 시각)의 경합만 막고 호출은 막지 못한다 — 다른 인스턴스가 대상을 읽은 뒤 이쪽이
 * 완료하고 회원이 재동의해 새 보고를 쓰면, 그쪽의 늦은 DELETE 가 그 보고를 지운다. 인스턴스를 늘리기 전에 단일 실행 잠금(ShedLock 등)을 둔다.
 *
 * <p><b>로그에는 파기 요청 ID · 건수 · 실패 요약(상태 코드 · 결과 코드 · 예외 클래스 단순 이름)만 남긴다.</b> 회원 ID 와 요청 URL 은 건강정보 동의
 * 철회 사실과 회원을 잇는다. 예외 객체 · 메시지 · {@link ReportPurgeRequest} 를 로거에 넘기지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportPurgeFacade implements ReportPurgeUseCase {

    private final ReportPurgeExecutionProcessor reportPurgeExecutionProcessor;
    private final ReportPurgeCommandPort reportPurgeCommandPort;
    private final ReportPurgeProperties reportPurgeProperties;
    private final JwtAuthProperties jwtAuthProperties;

    /** 한 항목의 처리 결과. */
    private enum Step {
        COMPLETED, PURGED, SKIPPED, FAILED, HALT
    }

    @Override
    public ReportPurgeRunResult purgeDue() {
        Duration settleWindow = jwtAuthProperties.accessExpiration().plus(reportPurgeProperties.completionMargin());
        List<ReportPurgeRequest> due = reportPurgeExecutionProcessor.findDue(LocalDateTime.now(), settleWindow, reportPurgeProperties.batchSize());

        int completed = 0;
        int purged = 0;
        int failed = 0;
        boolean halted = false;
        // 원격 호출이 요청마다 하나다 — 받는 쪽 API 가 회원 하나(가명 키 하나)를 단위로 지우므로 묶을 수 없다 (coding-conventions §8-5).
        for (ReportPurgeRequest request : due) {
            Step step = processOne(request, settleWindow);
            if (step == Step.HALT) {
                halted = true;
                break;
            }
            switch (step) {
                case COMPLETED -> completed++;
                case PURGED -> purged++;
                case FAILED -> failed++;
                default -> {
                    // SKIPPED: 다른 실행이 먼저 완료했다.
                }
            }
        }

        ReportPurgeRunResult result = new ReportPurgeRunResult(due.size(), completed, purged, failed, halted);
        log.info("report purge run finished due={} completed={} purged={} failed={} halted={}",
            result.due(), result.completed(), result.purged(), result.failed(), result.halted());
        return result;
    }

    @Override
    public int cleanUpCompleted() {
        int deleted = reportPurgeExecutionProcessor.deleteCompletedBefore(LocalDateTime.now().minus(reportPurgeProperties.retention()));
        log.info("report purge cleanup finished deleted={}", deleted);
        return deleted;
    }

    /** 예상 밖 예외(DB 장애 등)도 여기서 끊는다 — 한 항목이 회차 전체를 멈추지 않게. */
    private Step processOne(ReportPurgeRequest request, Duration settleWindow) {
        try {
            LocalDateTime callStartedAt = LocalDateTime.now();
            ReportPurgeCallResult result = reportPurgeCommandPort.purgeReporter(request.memberId());
            return switch (result.outcome()) {
                case PURGED -> recordSuccess(request, callStartedAt, settleWindow);
                case REJECTED, UNAVAILABLE -> recordFailure(request.id(), result.errorSummary());
                case CIRCUIT_OPEN -> {
                    // 호출이 나가지 않았으므로 시도로 세지 않는다. 남은 항목도 같은 서킷에 막히니 회차를 멈추고 다음 회차에 맡긴다.
                    log.warn("report purge run halted, circuit open requestId={}", request.id());
                    yield Step.HALT;
                }
            };
        } catch (RuntimeException exception) {
            log.error("report purge item failed unexpectedly requestId={} exception={}", request.id(), exception.getClass().getSimpleName());
            return Step.FAILED;
        }
    }

    private Step recordSuccess(ReportPurgeRequest request, LocalDateTime callStartedAt, Duration settleWindow) {
        SuccessRecord record = reportPurgeExecutionProcessor.recordSuccess(request, callStartedAt, LocalDateTime.now(), settleWindow);
        return switch (record) {
            case COMPLETED -> Step.COMPLETED;
            case AWAITING_SECOND_CALL -> Step.PURGED;
            case ALREADY_COMPLETED -> Step.SKIPPED;
        };
    }

    private Step recordFailure(long requestId, String errorSummary) {
        OptionalInt attemptCount = reportPurgeExecutionProcessor.recordFailure(requestId, errorSummary, LocalDateTime.now());
        if (attemptCount.isEmpty()) {
            return Step.SKIPPED;
        }
        int threshold = reportPurgeProperties.alertAttemptThreshold();
        if (attemptCount.getAsInt() >= threshold) {
            // 경보. 행을 지우거나 포기하지 않는다 — 파기는 끝날 때까지 다시 부른다.
            log.error("report purge attempts reached alert threshold requestId={} attemptCount={} threshold={} error={}",
                requestId, attemptCount.getAsInt(), threshold, errorSummary);
        } else {
            log.warn("report purge call failed requestId={} attemptCount={} error={}", requestId, attemptCount.getAsInt(), errorSummary);
        }
        return Step.FAILED;
    }
}
