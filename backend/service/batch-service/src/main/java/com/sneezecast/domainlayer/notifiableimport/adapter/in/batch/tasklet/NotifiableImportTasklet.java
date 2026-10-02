package com.sneezecast.domainlayer.notifiableimport.adapter.in.batch.tasklet;

import com.sneezecast.domainlayer.notifiableimport.adapter.in.batch.job.NotifiableImportJobParametersValidator;
import com.sneezecast.domainlayer.notifiableimport.application.command.NotifiableImportCommand;
import com.sneezecast.domainlayer.notifiableimport.application.port.in.NotifiableImportUseCase;
import java.time.LocalDateTime;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.stereotype.Component;

/**
 * 파라미터를 커맨드로 바꿔 유스케이스를 부른다. 예외는 잡지 않는다 — 전파되어 Step 이 FAILED 로 끝나고 메시지가 배치 메타데이터에 남는다.
 * 실행 요약 로그는 유스케이스가 남긴다 (성공 INFO · 실패 WARN).
 */
@Component
@RequiredArgsConstructor
public class NotifiableImportTasklet implements Tasklet {

    private final NotifiableImportUseCase notifiableImportUseCase;

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        Map<String, Object> jobParameters = chunkContext.getStepContext().getJobParameters();
        LocalDateTime runAt = NotifiableImportJobParametersValidator.runAt(jobParameters.get(NotifiableImportJobParametersValidator.RUN_AT));
        NotifiableImportCommand command = new NotifiableImportCommand(
            NotifiableImportJobParametersValidator.currentYear(jobParameters.get(NotifiableImportJobParametersValidator.YEAR), runAt), runAt);

        notifiableImportUseCase.importNotifiable(command);
        return RepeatStatus.FINISHED;
    }
}
