package com.sneezecast.domainlayer.schedule.adapter.out.batch;

import com.sneezecast.domainlayer.schedule.application.port.out.BatchJobExecutionQueryPort;
import com.sneezecast.domainlayer.schedule.application.port.out.query.RunningJobExecutionQueryResult;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.stereotype.Component;

/**
 * 배치 메타데이터에서 실행 중인(STARTING · STARTED · STOPPING) 잡을 읽는다.
 *
 * <p><b>시간대.</b> Spring Batch 5 는 메타 시각을 시간대 없는 {@code LocalDateTime.now()} 로 쓴다 — JVM 기본 시간대의 벽시계다. 그 행을 쓰는 것은
 * 이 상주 JVM 이거나 같은 이미지 · 같은 {@code -Duser.timezone} 으로 뜬 수동 실행 JVM 이므로, 읽을 때도 JVM 기본 시간대로 되돌린다. 설정 시간대
 * ({@code batch.schedule.time-zone})로 바꾸면 배포 시간대가 다를 때 경과 시간이 그 차이만큼 틀어진다.
 *
 * <p>이름마다 한 번씩 부르는 것은 N+1 이 아니다. {@code JobExplorer} 에 이름 여러 개를 받는 조회가 없고, 대상은 발화 한 번에 몇 개뿐인
 * 코드 상수 목록이다.
 */
@Component
@RequiredArgsConstructor
public class JobExplorerBatchJobExecutionAdapter implements BatchJobExecutionQueryPort {

    private final JobExplorer jobExplorer;

    @Override
    public List<RunningJobExecutionQueryResult> findRunning(Collection<String> jobNames) {
        return jobNames.stream()
            .flatMap(jobName -> jobExplorer.findRunningJobExecutions(jobName).stream()
                .map(execution -> toQueryResult(jobName, execution)))
            .toList();
    }

    private RunningJobExecutionQueryResult toQueryResult(String jobName, JobExecution execution) {
        // STARTING 은 아직 시작 시각이 없다. 생성 시각은 실행 행을 만들 때 항상 채워진다.
        LocalDateTime startedAt = execution.getStartTime() != null ? execution.getStartTime() : execution.getCreateTime();
        return new RunningJobExecutionQueryResult(jobName, execution.getId(), startedAt.atZone(ZoneId.systemDefault()).toInstant());
    }
}
