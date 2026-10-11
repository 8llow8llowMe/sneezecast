package com.sneezecast.domainlayer.aggregate.adapter.in.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sneezecast.domainlayer.aggregate.application.port.in.AggregateJobUseCase;
import com.sneezecast.domainlayer.aggregate.application.service.AggregateJobProperties;
import com.sneezecast.global.config.SurveillanceServiceSchedulingConfig;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

/**
 * 스위치 하나({@code surveillance.aggregate-job.scheduler-enabled})가 스케줄러 빈 · {@code @Scheduled} 처리기 · 기동 로그 · 설정 검증을 같은 뜻으로
 * 가르는지, 한 회차가 [마감 → 재계산] 순서로 돌고 단계 실패 · 겹친 트리거를 어떻게 다루는지 본다.
 */
@ExtendWith(OutputCaptureExtension.class)
class AggregateSchedulerTest {

    /** 나머지 값은 yml 기본값과 같은 꼴로 준다 — 켜졌을 때 식 해석까지 통과해야 기동한다. 첫 발화는 테스트 수명보다 한참 뒤다. */
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(PropertiesConfig.class, SurveillanceServiceSchedulingConfig.class, AggregateScheduler.class,
            AggregateSchedulerStatusLogger.class)
        .withBean(AggregateJobUseCase.class, () -> mock(AggregateJobUseCase.class))
        .withPropertyValues("surveillance.aggregate-job.initial-delay=PT1H", "surveillance.aggregate-job.fixed-delay=PT10M",
            "surveillance.aggregate-job.close-cron=0 10 0 * * MON", "surveillance.aggregate-job.close-grace=PT9M");

    @ParameterizedTest(name = "scheduler-enabled=\"{0}\"")
    @ValueSource(strings = {"true", "TRUE"})
    @DisplayName("true(대소문자 무시)면 스케줄러 빈과 @Scheduled 처리기가 올라가고 INFO 를 남긴다")
    void enabledRegistersScheduler(String value, CapturedOutput output) {
        contextRunner.withPropertyValues("surveillance.aggregate-job.scheduler-enabled=" + value).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(AggregateScheduler.class);
            assertThat(context).hasSingleBean(ScheduledAnnotationBeanPostProcessor.class);
        });
        assertThat(output.getAll()).contains("aggregate scheduler enabled").doesNotContain("aggregate scheduler disabled");
    }

    @Test
    @DisplayName("false 거나 키가 없으면 꺼지고 기동 때 WARN 을 남긴다 — 집계가 조용히 멈추지 않게")
    void disabledWarnsAtStartup(CapturedOutput output) {
        contextRunner.withPropertyValues("surveillance.aggregate-job.scheduler-enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(AggregateScheduler.class);
            assertThat(context).doesNotHaveBean(ScheduledAnnotationBeanPostProcessor.class);
        });
        contextRunner.run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(AggregateScheduler.class));
        assertThat(output.getAll()).contains("WARN").contains("aggregate scheduler disabled - set SURVEILLANCE_AGGREGATE_JOB_SCHEDULER_ENABLED=true");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
        "surveillance.aggregate-job.scheduler-enabled=yes",
        "surveillance.aggregate-job.fixed-delay=PT0S",
        "surveillance.aggregate-job.initial-delay=-PT1M",
        "surveillance.aggregate-job.close-cron=0 10 0 * MON",
        "surveillance.aggregate-job.close-grace=P2D",
        "surveillance.aggregate-job.close-grace=-PT1M"
    })
    @DisplayName("잘못된 값은 기동 실패다 — 조용히 꺼지거나 엉뚱한 때에 마감하지 않게")
    void invalidValuesFailStartup(String property) {
        // 잘못된 cron 은 설정 검증과 @Scheduled 처리 중 먼저 만나는 쪽이 막는다(빈 생성 순서는 정해져 있지 않다). 어느 쪽이든 기동은 실패한다.
        contextRunner.withPropertyValues("surveillance.aggregate-job.scheduler-enabled=true", property)
            .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("application.yml 기본값 — 꺼짐, 첫 회차 1분 뒤 · 10분 주기, 월요일 00:10 마감, 유예 9분(≤ cron 시각, 1분 여유)")
    void bindsDefaultsFromApplicationYml() {
        new ApplicationContextRunner().withUserConfiguration(PropertiesConfig.class).withInitializer(new ConfigDataApplicationContextInitializer())
            .run(context -> assertThat(context.getBean(AggregateJobProperties.class))
                .isEqualTo(new AggregateJobProperties("false", Duration.ofMinutes(1), Duration.ofMinutes(10), "0 10 0 * * MON", Duration.ofMinutes(9))));
    }

    @Test
    @DisplayName("설정 검증 메시지는 설정 키를 싣는다 — 어느 값이 틀렸는지 기동 로그에서 바로 찾게")
    void validationMessageNamesKey() {
        Duration minute = Duration.ofMinutes(1);
        assertThatThrownBy(() -> new AggregateJobProperties("yes", minute, minute, "0 10 0 * * MON", minute))
            .hasMessageContaining("SURVEILLANCE_AGGREGATE_JOB_SCHEDULER_ENABLED");
        assertThatThrownBy(() -> new AggregateJobProperties("true", minute, Duration.ZERO, "0 10 0 * * MON", minute))
            .hasMessageContaining("surveillance.aggregate-job.fixed-delay");
        assertThatThrownBy(() -> new AggregateJobProperties("true", minute, minute, "0 10 0 * MON", minute))
            .hasMessageContaining("surveillance.aggregate-job.close-cron");
        assertThatThrownBy(() -> new AggregateJobProperties("true", minute, minute, "0 10 0 * * MON", Duration.ofDays(2)))
            .hasMessageContaining("surveillance.aggregate-job.close-grace");
        assertThatCode(() -> new AggregateJobProperties(null, Duration.ZERO, minute, "0 10 0 * * MON", Duration.ZERO)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("주기 회차와 마감 cron 모두 [지난 주 마감 → 현재 주 재계산] 순서로 돈다")
    void runsCloseThenRecalculate() {
        AggregateJobUseCase useCase = mock(AggregateJobUseCase.class);
        AggregateScheduler scheduler = new AggregateScheduler(useCase);

        scheduler.recalculate();
        scheduler.closeWeek();

        InOrder order = inOrder(useCase);
        for (int run = 0; run < 2; run++) {
            order.verify(useCase).closeOverdueWeeks();
            order.verify(useCase).recalculateCurrentWeek();
        }
    }

    @Test
    @DisplayName("마감이 실패해도 현재 주 재계산은 돌고, 어느 단계의 예외도 스케줄러 밖으로 나가지 않는다")
    void stageFailureIsIsolated(CapturedOutput output) {
        AggregateJobUseCase useCase = mock(AggregateJobUseCase.class);
        when(useCase.closeOverdueWeeks()).thenThrow(new IllegalStateException("close"));
        when(useCase.recalculateCurrentWeek()).thenThrow(new IllegalStateException("recalculate"));
        AggregateScheduler scheduler = new AggregateScheduler(useCase);

        assertThatCode(scheduler::recalculate).doesNotThrowAnyException();

        verify(useCase).recalculateCurrentWeek();
        assertThat(output.getAll()).contains("aggregate close failed trigger=periodic exception=IllegalStateException")
            .contains("aggregate recalculation failed trigger=periodic exception=IllegalStateException");
    }

    @Test
    @DisplayName("회차가 도는 동안 들어온 다른 트리거는 건너뛴다 — 한 번에 하나만 돈다")
    void overlappingTriggerIsSkipped(CapturedOutput output) throws Exception {
        AggregateJobUseCase useCase = mock(AggregateJobUseCase.class);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            started.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return List.of();
        }).when(useCase).closeOverdueWeeks();
        AggregateScheduler scheduler = new AggregateScheduler(useCase);

        CompletableFuture<Void> running = CompletableFuture.runAsync(scheduler::recalculate);
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        scheduler.closeWeek();
        verify(useCase, never()).recalculateCurrentWeek();
        release.countDown();
        running.get(5, TimeUnit.SECONDS);

        verify(useCase, times(1)).closeOverdueWeeks();
        verify(useCase, times(1)).recalculateCurrentWeek();
        assertThat(output.getAll()).contains("aggregate run skipped - previous run still in progress trigger=close-cron");
    }

    @EnableConfigurationProperties(AggregateJobProperties.class)
    static class PropertiesConfig {
    }
}
