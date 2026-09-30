package com.sneezecast.domainlayer.schedule.adapter.in.scheduler;

import org.springframework.boot.autoconfigure.condition.AllNestedConditions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * 스케줄 트리거를 등록하고 스케줄러를 시작할지 정하는 조건. 두 스위치가 <b>모두</b> 맞아야 켜진다.
 *
 * <ul>
 *   <li>{@code batch.schedule.enabled=true} — 테스트 컨텍스트나 개발자 PC 가 실제 외부 API 적재를 시작하지 않게 기본은 꺼짐이다.</li>
 *   <li>{@code spring.batch.job.enabled=false} (또는 미지정) — 잡 하나만 돌리려고 {@code --spring.batch.job.enabled=true} 로 띄운
 *       두 번째 JVM 은 그 잡을 끝내고 죽어야 한다. 거기에 스케줄까지 붙으면 짧은 수명 동안 또 다른 잡을 띄운다.</li>
 * </ul>
 *
 * <p><b>왜 {@code @ConditionalOnExpression} 이 아닌가.</b> SpEL 조건식
 * ({@code "${batch.schedule.enabled:false} and not ${spring.batch.job.enabled:false}"})은 값을 치환한 <b>문자열을 SpEL 로 파싱</b>한다.
 * 그래서 값이 불리언 리터럴이 아니면 파싱 단계에서 죽는다. 실제로 그럴 수 있다 — 배포 설정의 {@code ${BATCH_SCHEDULE_ENABLED:-}} 같은 치환은
 * 변수를 <b>빈 문자열</b>로 만들지 부재로 만들지 않는다. Spring 의 {@code ${x:default}} 는 값이 없을 때만 기본값을 쓰므로 프로퍼티는
 * {@code ""} 가 되고, 조건식은 {@code " and not false"} 가 되어 SpEL 파싱 실패 → 컨텍스트 refresh 실패 → 재시작 반복이다.
 * 운영자가 {@code yes} 나 {@code 1} 을 적어도 같다.
 *
 * <p>{@code @ConditionalOnProperty} 는 값을 문자열로 비교할 뿐이라 {@code ""} · {@code yes} · {@code 1} 이 들어와도 파싱이 죽지 않고
 * 조용히 <b>"꺼짐"</b> 으로 떨어진다. 스케줄 스위치의 오타가 서비스 기동을 막는 것보다 스케줄이 안 도는 쪽이 낫다 — 후자는 적재 시각이
 * 멈추는 것으로 드러나지만 전자는 배치 서비스를 통째로 죽인다.
 */
public class ScheduleEnabledCondition extends AllNestedConditions {

    public ScheduleEnabledCondition() {
        // 트리거 빈 자체의 등록 여부를 가르는 조건이라 REGISTER_BEAN 단계다.
        super(ConfigurationPhase.REGISTER_BEAN);
    }

    @ConditionalOnProperty(name = "batch.schedule.enabled", havingValue = "true")
    static class ScheduleSwitchOn {
    }

    @ConditionalOnProperty(name = "spring.batch.job.enabled", havingValue = "false", matchIfMissing = true)
    static class NotManualLaunchRun {
    }
}
