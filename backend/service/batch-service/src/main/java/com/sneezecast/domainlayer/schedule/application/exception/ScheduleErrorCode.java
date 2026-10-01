package com.sneezecast.domainlayer.schedule.application.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 스케줄 발화 에러코드.
 *
 * <p>batch-service 에는 웹 API 가 없어 예외 핸들러를 두지 않는다. 예외는 Quartz 의 {@code JobExecutionException} 으로 바뀌어 로그에 남고
 * {@code batch.schedule.fire{result=failed}} 지표로 드러난다. {@code httpStatus} 는 컨벤션의 3필드 형식을 맞추려는 것이고 응답으로 나가지 않는다.
 */
@Getter
@RequiredArgsConstructor
public enum ScheduleErrorCode {

    JOB_NOT_FOUND("SCHEDULE_001", "스케줄할 배치 잡을 찾을 수 없습니다. (jobName=%s)", HttpStatus.INTERNAL_SERVER_ERROR),
    LAUNCH_FAILED("SCHEDULE_002", "배치 잡 실행이 거부됐습니다. (jobName=%s, reason=%s)", HttpStatus.CONFLICT),
    DUPLICATE_JOB_NAME("SCHEDULE_003", "같은 이름의 배치 잡이 둘 이상입니다. (jobName=%s)", HttpStatus.INTERNAL_SERVER_ERROR);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;
}
