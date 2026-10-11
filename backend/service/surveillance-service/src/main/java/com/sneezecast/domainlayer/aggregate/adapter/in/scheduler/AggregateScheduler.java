package com.sneezecast.domainlayer.aggregate.adapter.in.scheduler;

import com.sneezecast.domainlayer.aggregate.application.port.in.AggregateJobUseCase;
import java.util.concurrent.locks.ReentrantLock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 집계 재계산 · 주 마감 스케줄 (entity-design §2-2, architecture-guide §5 ②). {@code surveillance.aggregate-job.scheduler-enabled} 가
 * {@code true}(대소문자 무시)일 때만 빈이 생긴다 — yml 기본은 꺼짐(테스트 컨텍스트가 공용 H2 를 건드리지 않게)이고 배포(compose)가 켠다.
 *
 * <p>두 트리거 모두 [지난 주 마감 → 현재 주 재계산] 한 회차를 돈다. 주기 회차가 놓친 마감(서버 중단)을 따라잡고, 월요일 00:10 cron 은 지난 주를 제때
 * 닫는다. 마감 직후 현재 주를 다시 세는 것은 방금 마감한 주가 현재 주의 기준선 · 전주가 되기 때문이다.
 *
 * <p><b>한 번에 하나만 돈다.</b> 인스턴스 안에서는 회차를 잠금으로 감싸 겹치는 트리거를 건너뛴다(Boot 기본 스케줄러 스레드도 1개다). 1단계는
 * surveillance 인스턴스 1개로 운영한다 — 늘리면 인스턴스 사이 잠금(ShedLock)을 이 두 메서드에 붙인다. 붙이지 않고 늘리면 같은 칸 insert 가 겹쳐
 * 한쪽 회차가 UK 위반으로 실패한다(다음 회차가 update 로 맞추지만, 마감 잡이 겹치면 판정 시점이 갈린다).
 *
 * <p>예외는 단계마다 최상위에서 잡아 로그만 남긴다 — 마감이 실패해도 현재 주 재계산은 돈다. 실패한 단계는 다음 회차에 다시 돈다. 집계 쿼리는 익명
 * 수치만 다루고 {@code reporter_key} 를 싣지 않으므로 스택트레이스를 남긴다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "surveillance.aggregate-job.scheduler-enabled", havingValue = "true")
public class AggregateScheduler {

    private final AggregateJobUseCase aggregateJobUseCase;
    private final ReentrantLock runLock = new ReentrantLock();

    /** 한 회차가 끝난 뒤 {@code fixed-delay}(기본 10분) 만큼 쉬고 다음 회차를 돈다 — 회차가 길어져도 겹치지 않는다. */
    @Scheduled(initialDelayString = "${surveillance.aggregate-job.initial-delay}", fixedDelayString = "${surveillance.aggregate-job.fixed-delay}")
    public void recalculate() {
        run("periodic");
    }

    /** 주 마감 시각(기본 월요일 00:10). 시각은 배포 시간대와 상관없이 KST 로 읽는다. */
    @Scheduled(cron = "${surveillance.aggregate-job.close-cron}", zone = "Asia/Seoul")
    public void closeWeek() {
        run("close-cron");
    }

    private void run(String trigger) {
        if (!runLock.tryLock()) {
            log.warn("aggregate run skipped - previous run still in progress trigger={}", trigger);
            return;
        }
        try {
            try {
                aggregateJobUseCase.closeOverdueWeeks();
            } catch (RuntimeException exception) {
                log.error("aggregate close failed trigger={} exception={}", trigger, exception.getClass().getSimpleName(), exception);
            }
            try {
                aggregateJobUseCase.recalculateCurrentWeek();
            } catch (RuntimeException exception) {
                log.error("aggregate recalculation failed trigger={} exception={}", trigger, exception.getClass().getSimpleName(), exception);
            }
        } finally {
            runLock.unlock();
        }
    }
}
