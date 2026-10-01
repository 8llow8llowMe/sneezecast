package com.sneezecast.domainlayer.schedule.application.port.out;

import com.sneezecast.domainlayer.schedule.application.port.out.query.RunningJobExecutionQueryResult;
import java.util.Collection;
import java.util.List;

/** 배치 메타데이터({@code BATCH_*})에서 아직 끝나지 않은 실행을 읽는다. */
public interface BatchJobExecutionQueryPort {

    List<RunningJobExecutionQueryResult> findRunning(Collection<String> jobNames);
}
