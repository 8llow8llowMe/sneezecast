package com.sneezecast.domainlayer.schedule.adapter.in.scheduler;

import org.springframework.boot.autoconfigure.quartz.SchedulerFactoryBeanCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

/**
 * 주기 실행 트리거와 스케줄러 시작 스위치.
 *
 * <p><b>왜 프로세스 안 스케줄러인가.</b> 배포 호스트 cron 은 컨테이너 밖에 산다 — 어느 호스트의 crontab 에 무엇이 걸려 있는지가 저장소에
 * 남지 않고, 컨테이너를 옮기면 조용히 사라진다. 게다가 cron 이 부르는 것은 컨테이너 안의 두 번째 JVM 이라 메모리 상한을 나눠 쓰고
 * Eureka 에 중복 등록된다. 상시 기동하는 batch-service 가 이미 있으니 그 프로세스가 스스로 시각을 지키는 편이 배포 단위와 스케줄 단위를
 * 일치시킨다.
 *
 * <p><b>왜 메모리 잡 스토어인가.</b> 인스턴스가 하나이고 트리거 정의가 전부 이 코드에 있어 영속할 상태가 없다. JDBC 스토어를 쓰면
 * surveillance 스키마에 {@code QRTZ_*} 테이블 열두 개가 더해지는데, 그것이 지켜주는 것(다중 인스턴스 배타 실행, 재기동 후 misfire 복원)은
 * 지금 필요 없다. 재기동 중 놓친 발화는 다음 주기가 따라잡는다.
 *
 * <p><b>잡을 추가하는 방법.</b> 적재 잡이 생기면 그 잡의 {@code JobDetail} 과 {@code Trigger} 를 <b>이 클래스에</b> {@code @Bean} 으로 더한다.
 * 부트 {@code QuartzAutoConfiguration} 이 컨텍스트의 {@code JobDetail} · {@code Trigger} 빈을 모두 스케줄러에 등록하므로 별도 등록 코드는 없다.
 * 이 클래스 밖에 두면 {@link ScheduleEnabledCondition} 이 닿지 않아 수동 실행 JVM 에도 트리거가 붙는다. 트리거를 만들 때는 다음을 지킨다.
 * <ul>
 *   <li>cron 에 시간대를 명시한다 ({@code CronScheduleBuilder.inTimeZone}). cron 이 뜻하는 시각은 배포 환경의 {@code -Duser.timezone} 이
 *       아니라 원천의 갱신 주기에서 나온다.</li>
 *   <li>misfire 는 {@code withMisfireHandlingInstructionFireAndProceed()} 로 둔다. Quartz 기본 정책은 재시작 중 놓친 발화를 버려서, 주 1회
 *       잡이 한 번 빠지면 자료가 한 주 더 낡는다.</li>
 *   <li>{@code JobDetail} 은 {@code storeDurably()} 로 만든다 — 트리거가 아직 붙지 않은 상태로도 스케줄러에 남아야 등록 순서에 걸리지 않는다.</li>
 * </ul>
 * 지금은 등록할 잡이 없어서 스케줄러는 빈 채로 뜬다.
 *
 * <p><b>수동 실행 JVM 에서는 켜지지 않는다.</b> {@code --spring.batch.job.enabled=true} 로 띄운 두 번째 JVM 은 잡 하나를 돌리고 끝나야 하는데,
 * 거기에 스케줄 트리거까지 붙으면 그 짧은 수명 동안 또 다른 잡을 띄울 수 있다. {@link ScheduleEnabledCondition} 이 그 조합을 배제한다.
 */
@Configuration
@Conditional(ScheduleEnabledCondition.class)
public class QuartzScheduleConfig {

    /**
     * 스케줄러 시작 스위치. {@code application.yml} 의 {@code spring.quartz.auto-startup} 은 <b>고정 false</b> 이고, 이 빈이 조건을 통과한
     * 컨텍스트에서만 true 로 되돌린다.
     *
     * <p>프로퍼티에 {@code ${batch.schedule.enabled:false}} 를 그대로 꽂아 두면 값이 빈 문자열일 때 불리언 바인딩이 깨진다(원인은
     * {@link ScheduleEnabledCondition} javadoc). 시작 여부를 조건과 같은 판정에 묶으면 그 경로가 통째로 사라진다 — 조건이 참이 아니면
     * 트리거도 없고 스케줄러도 standby 로 남으므로, 수동 실행 JVM 은 등록도 시작도 하지 않는다.
     */
    @Bean
    public SchedulerFactoryBeanCustomizer scheduleAutoStartupCustomizer() {
        return factory -> factory.setAutoStartup(true);
    }
}
