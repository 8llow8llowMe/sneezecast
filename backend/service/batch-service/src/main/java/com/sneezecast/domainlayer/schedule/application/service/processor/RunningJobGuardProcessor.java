package com.sneezecast.domainlayer.schedule.application.service.processor;

import com.sneezecast.domainlayer.schedule.application.port.out.BatchJobExecutionQueryPort;
import com.sneezecast.domainlayer.schedule.application.port.out.query.RunningJobExecutionQueryResult;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 겹치면 안 되는 잡이 아직 돌고 있는지 배치 메타데이터로 본다.
 *
 * <p>Quartz 의 {@code @DisallowConcurrentExecution} 은 같은 JobKey 의 중복 발화만 막는다. 다른 잡끼리는 JobKey 가 달라 닿지 않고, 무엇보다
 * <b>{@code --spring.batch.job.name} 으로 띄운 수동 실행 JVM</b> 의 실행은 이 프로세스의 Quartz 가 아예 모른다. 두 JVM 이 함께 보는 것은
 * 같은 데이터소스의 {@code BATCH_JOB_EXECUTION} 뿐이라, 수동 실행이 같은 잡(또는 겹침 목록의 잡)을 돌리고 있으면 그 STARTED 행으로 막힌다.
 *
 * <p><b>오래된 STARTED 는 무시한다.</b> JVM 이 OOM 이나 {@code docker kill} 로 죽으면 JobExecution 이 STARTED 인 채 남는다. 그것을 그대로 믿으면
 * 스케줄이 영원히 막혀 자료가 무한정 낡는다 — 잘못 겹칠 위험보다 영원히 안 도는 쪽이 나쁘다. 대신 조용히 넘기지 않고 ERROR 로 남겨 사람이 그 행을
 * 정리하게 한다 ({@code batch.schedule.stale-running-after}, 기본 6시간).
 *
 * <p><b>best-effort 다.</b> 이 조회와 {@code JobLauncher.run} 사이에 틈이 있어, 그 사이 수동 실행이 시작되면 막지 못한다. 같은 {@code runAt}
 * 이 아니면 Spring Batch 도 막지 않는다. 단일 인스턴스 + 사람의 수동 실행이라 이 틈은 받아들인다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RunningJobGuardProcessor {

    private final BatchJobExecutionQueryPort batchJobExecutionQueryPort;

    /**
     * @param now        판정 기준 시각 (발화 시각)
     * @param staleAfter 이보다 오래전에 시작한 실행은 죽은 JVM 의 잔재로 본다
     * @return 이번 발화를 막는 잡 이름. 막는 것이 없으면 비어 있다
     */
    public Optional<String> findBlocking(List<String> jobNames, Instant now, Duration staleAfter) {
        if (jobNames == null || jobNames.isEmpty()) {
            return Optional.empty();
        }
        Instant staleBefore = now.minus(staleAfter);
        Optional<String> blocking = Optional.empty();
        for (RunningJobExecutionQueryResult running : batchJobExecutionQueryPort.findRunning(jobNames)) {
            if (running.startedAt().isAfter(staleBefore)) {
                // 첫 번째만 돌려주되, 뒤에 섞인 방치 실행도 로그로 드러나게 순회는 끝까지 돈다.
                if (blocking.isEmpty()) {
                    blocking = Optional.of(running.jobName());
                }
                continue;
            }
            log.error("Abandoned running execution ignored. jobName={} executionId={} startedAt={} staleAfter={}",
                running.jobName(), running.executionId(), running.startedAt(), staleAfter);
        }
        return blocking;
    }
}
