package com.sneezecast.domainlayer.districtimport.adapter.in.batch.tasklet;

import com.sneezecast.domainlayer.districtimport.adapter.in.batch.job.DistrictImportJobParametersValidator;
import com.sneezecast.domainlayer.districtimport.application.command.DistrictImportCommand;
import com.sneezecast.domainlayer.districtimport.application.model.DistrictImportResult;
import com.sneezecast.domainlayer.districtimport.application.port.in.DistrictImportUseCase;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.stereotype.Component;

/**
 * 파라미터를 커맨드로 바꿔 유스케이스를 부른다. 예외는 잡지 않는다 — 전파되어 Step 이 FAILED 로 끝나고 메시지가 배치 메타데이터에 남는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DistrictImportTasklet implements Tasklet {

    private final DistrictImportUseCase districtImportUseCase;

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        Map<String, Object> jobParameters = chunkContext.getStepContext().getJobParameters();
        DistrictImportCommand command = new DistrictImportCommand(
            DistrictImportJobParametersValidator.year(jobParameters.get(DistrictImportJobParametersValidator.YEAR)),
            DistrictImportJobParametersValidator.allowMassRetire(jobParameters.get(DistrictImportJobParametersValidator.ALLOW_MASS_RETIRE)));

        DistrictImportResult result = districtImportUseCase.importDistricts(command);
        log.info("districtImportJob done. year={} allowMassRetire={} fetched={} upserted={} retired={}",
            result.year(), command.allowMassRetire(), result.fetched(), result.upserted(), result.retired());
        return RepeatStatus.FINISHED;
    }
}
