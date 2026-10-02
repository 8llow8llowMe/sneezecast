package com.sneezecast.domainlayer.schedule.adapter.in.scheduler;

import com.sneezecast.domainlayer.notifiableimport.adapter.in.batch.job.NotifiableImportJobConfig;
import com.sneezecast.global.properties.BatchScheduleProperties;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TimeZone;
import org.quartz.CronScheduleBuilder;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
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
 * 지금 필요 없다. 내려가 있던 동안 놓친 발화는 재기동 뒤 보충되지 않는다 — 배포는 발화 시각을 피하고, 놓쳤으면 수동 실행한다.
 *
 * <p><b>실행 흐름.</b> 트리거 → {@link SpringBatchLaunchQuartzJob} → {@code ScheduledJobLaunchUseCase} → 겹침 가드
 * ({@code RunningJobGuardProcessor}) → {@code BatchJobLaunchPort} → {@code JobLauncher.run}. 잡마다 Quartz 잡 클래스를 따로 두지 않는다.
 *
 * <p><b>잡을 추가하는 방법.</b> 주간 적재 잡이 생기면 {@code BatchScheduleProperties} 에 cron 필드를 더하고, 그 잡의 {@code JobDetail} 과
 * {@code Trigger} 를 <b>이 클래스에</b> {@code @Bean} 두 개로 더한다 — 반드시 {@link #newJobDetail} · {@link #newCronTrigger} 로 만든다.
 * {@link #notifiableImportJobDetail} · {@link #notifiableImportTrigger} 가 그 모양이다.
 * 부트 {@code QuartzAutoConfiguration} 이 컨텍스트의 {@code JobDetail} · {@code Trigger} 빈을 모두 스케줄러에 등록하므로 별도 등록 코드는 없다.
 * 이 클래스 밖에 두면 {@link ScheduleEnabledCondition} 이 닿지 않아 수동 실행 JVM 에도 트리거가 붙는다. {@code districtImportJob} 은 수동 전용이라
 * 트리거가 없다.
 *
 * <p><b>수동 실행 JVM 에서는 켜지지 않는다.</b> {@code --spring.batch.job.enabled=true} 로 띄운 두 번째 JVM 은 잡 하나를 돌리고 끝나야 하는데,
 * 거기에 스케줄 트리거까지 붙으면 그 짧은 수명 동안 또 다른 잡을 띄울 수 있다. {@link ScheduleEnabledCondition} 이 그 조합을 배제한다.
 */
@Configuration
@Conditional(ScheduleEnabledCondition.class)
public class QuartzScheduleConfig {

    private static final String TRIGGER_SUFFIX = "Trigger";

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

    /**
     * 전수신고 적재(매주 화 05:00 KST). 겹침 금지 목록은 자기 자신뿐이다 — {@code districtImportJob} 과는 쓰는 테이블이 다르고, 스케줄 잡끼리는
     * Quartz 스레드가 하나라 겹치지 않는다.
     */
    @Bean
    public JobDetail notifiableImportJobDetail() {
        return newJobDetail(NotifiableImportJobConfig.JOB_NAME);
    }

    @Bean
    public Trigger notifiableImportTrigger(
        BatchScheduleProperties batchScheduleProperties,
        @Qualifier("notifiableImportJobDetail") JobDetail notifiableImportJobDetail
    ) {
        return newCronTrigger(notifiableImportJobDetail, batchScheduleProperties.notifiableCron(), batchScheduleProperties);
    }

    /**
     * 배치 잡 하나를 띄우는 {@code JobDetail}. JobKey 이름은 배치 잡 이름과 같다.
     *
     * <ul>
     *   <li>{@code storeDurably()} — 트리거가 아직 붙지 않은 상태로도 스케줄러에 남아야 등록 순서에 걸리지 않는다.</li>
     *   <li>겹침 금지 목록에는 <b>자기 자신을 항상 맨 앞에 넣는다</b>. 같은 JobKey 는 {@code @DisallowConcurrentExecution} 이 막지만, 수동 실행
     *       JVM 이 같은 잡을 돌리고 있는 것은 메타데이터로만 보인다.</li>
     * </ul>
     *
     * @param blockedBy 이 잡 말고도 돌고 있으면 이번 발화를 넘길 잡 이름들 (같은 테이블을 쓰거나 읽는 잡)
     */
    static JobDetail newJobDetail(String jobName, String... blockedBy) {
        Set<String> mustNotBeRunning = new LinkedHashSet<>();
        mustNotBeRunning.add(jobName);
        for (String name : blockedBy) {
            if (name != null && !name.isBlank()) {
                mustNotBeRunning.add(name.trim());
            }
        }
        return JobBuilder.newJob(SpringBatchLaunchQuartzJob.class)
            .withIdentity(jobName)
            .storeDurably()
            .usingJobData(SpringBatchLaunchQuartzJob.JOB_NAME_KEY, jobName)
            .usingJobData(SpringBatchLaunchQuartzJob.BLOCKED_BY_KEY, String.join(SpringBatchLaunchQuartzJob.BLOCKED_BY_SEPARATOR, mustNotBeRunning))
            .build();
    }

    /**
     * {@code JobDetail} 에 붙는 cron 트리거. TriggerKey 이름은 {@code <잡 이름>Trigger}.
     *
     * <ul>
     *   <li>시간대는 {@code batch.schedule.time-zone} 으로 못박는다. cron 이 뜻하는 시각은 배포 환경의 {@code -Duser.timezone} 이 아니라 원천의
     *       갱신 주기에서 나온다.</li>
     *   <li>misfire 는 {@code FireAndProceed} — 앞 잡이 하나뿐인 Quartz 스레드를 오래 쥐어 발화가 늦어지면, 그 사이 몇 번을 놓쳤든 스레드가
     *       풀리는 즉시 한 번만 돌고 다음 주기로 돌아간다. cron 트리거의 기본(smart policy)도 같은 동작이지만 의도를 명시해 고정한다. 프로세스가
     *       내려가 있던 동안의 발화는 메모리 스토어라 이 정책과 무관하게 보충되지 않는다.</li>
     * </ul>
     *
     * @param cron Quartz cron (초가 맨 앞, 6~7 필드)
     */
    static Trigger newCronTrigger(JobDetail jobDetail, String cron, BatchScheduleProperties batchScheduleProperties) {
        return TriggerBuilder.newTrigger()
            .forJob(jobDetail)
            .withIdentity(jobDetail.getKey().getName() + TRIGGER_SUFFIX)
            .withSchedule(CronScheduleBuilder.cronSchedule(cron)
                .inTimeZone(TimeZone.getTimeZone(batchScheduleProperties.zoneId()))
                .withMisfireHandlingInstructionFireAndProceed())
            .build();
    }
}
