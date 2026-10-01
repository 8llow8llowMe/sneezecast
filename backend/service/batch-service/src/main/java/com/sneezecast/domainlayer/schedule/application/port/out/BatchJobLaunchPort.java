package com.sneezecast.domainlayer.schedule.application.port.out;

import java.util.Map;

/**
 * 이름으로 배치 잡을 띄운다. Spring Batch 의 {@code Job} · {@code JobLauncher} 타입이 application 계층으로 새지 않게 문자열만 주고받는다.
 */
public interface BatchJobLaunchPort {

    /**
     * @param identifyingParameters    JobInstance 를 가르는 파라미터. 같은 값으로 다시 돌리면 이미 완료된 JobInstance 라 거절된다
     * @param nonIdentifyingParameters 기록용 파라미터. JobInstance 식별에 쓰이지 않는다
     * @return 시작한 JobExecution id
     * @throws com.sneezecast.domainlayer.schedule.application.exception.ScheduleException 잡이 없거나 실행이 거부된 경우
     */
    long launch(String jobName, Map<String, String> identifyingParameters, Map<String, String> nonIdentifyingParameters);
}
