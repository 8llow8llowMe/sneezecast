package com.sneezecast.domainlayer.member.adapter.in.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sneezecast.domainlayer.member.application.port.in.ReportPurgeUseCase;
import com.sneezecast.global.config.AuthServiceSchedulingConfig;
import com.sneezecast.global.properties.ReportPurgeProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

/**
 * 스위치 하나({@code auth.report-purge.scheduler-enabled})가 스케줄러 빈 · {@code @Scheduled} 처리기 · 기동 로그 · 설정 검증을 같은 뜻으로 가르는지 본다.
 */
@ExtendWith(OutputCaptureExtension.class)
class ReportPurgeSchedulerTest {

    private static final long MEMBER_ID = 7350912846153L;

    /** 나머지 값은 yml 기본값과 같은 꼴로 준다 — 켜졌을 때 식 해석까지 통과해야 기동한다. 첫 발화는 테스트 수명보다 한참 뒤다. */
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(PropertiesConfig.class, AuthServiceSchedulingConfig.class, ReportPurgeScheduler.class, ReportPurgeSchedulerStatusLogger.class)
        .withBean(ReportPurgeUseCase.class, () -> mock(ReportPurgeUseCase.class))
        .withPropertyValues("auth.report-purge.initial-delay=PT1H", "auth.report-purge.fixed-delay=PT5M", "auth.report-purge.completion-margin=PT5M",
            "auth.report-purge.batch-size=50", "auth.report-purge.alert-attempt-threshold=12", "auth.report-purge.retention=P365D",
            "auth.report-purge.cleanup-cron=0 30 4 * * *");

    @ParameterizedTest(name = "scheduler-enabled=\"{0}\"")
    @ValueSource(strings = {"true", "TRUE"})
    @DisplayName("true(대소문자 무시)면 스케줄러 빈과 @Scheduled 처리기가 올라가고 INFO 를 남긴다 — 설정 검증과 조건의 판정이 같다")
    void enabledRegistersScheduler(String value, CapturedOutput output) {
        contextRunner.withPropertyValues("auth.report-purge.scheduler-enabled=" + value).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(ReportPurgeScheduler.class);
            assertThat(context).hasSingleBean(ScheduledAnnotationBeanPostProcessor.class);
        });
        assertThat(output.getAll()).contains("report purge scheduler enabled").doesNotContain("report purge scheduler disabled");
    }

    @Test
    @DisplayName("false 면 꺼지고 기동 때 WARN 을 남긴다 — 법적 파기 기능이 조용히 꺼지지 않게")
    void disabledWarnsAtStartup(CapturedOutput output) {
        contextRunner.withPropertyValues("auth.report-purge.scheduler-enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(ReportPurgeScheduler.class);
            assertThat(context).doesNotHaveBean(ScheduledAnnotationBeanPostProcessor.class);
        });
        assertThat(output.getAll()).contains("WARN").contains("report purge scheduler disabled - set AUTH_REPORT_PURGE_SCHEDULER_ENABLED=true");
    }

    @Test
    @DisplayName("스위치 키가 없으면 꺼짐이다 — 테스트 컨텍스트가 surveillance 를 부르지 않는다")
    void missingSwitchDisablesScheduler() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(ReportPurgeScheduler.class);
        });
    }

    @ParameterizedTest(name = "scheduler-enabled=\"{0}\"")
    @ValueSource(strings = {"", "yes", "1", "on"})
    @DisplayName("true · false 가 아닌 값은 기동 실패다 — 조건은 꺼짐으로 읽을 값이라, 조용히 꺼지는 대신 기동을 막는다")
    void ambiguousValuesFailStartup(String value) {
        contextRunner.withPropertyValues("auth.report-purge.scheduler-enabled=" + value)
            .run(context -> assertThat(context).hasFailed()
                .getFailure().rootCause().hasMessageContaining("auth.report-purge.scheduler-enabled"));
    }

    @Test
    @DisplayName("회차 · 정리의 예외는 최상위에서 잡고 클래스 이름만 남긴다 — 메시지 · 스택에 실린 요청 URL 이 로그로 새지 않는다")
    void swallowsExceptionsWithoutMessage(CapturedOutput output) {
        ReportPurgeUseCase useCase = mock(ReportPurgeUseCase.class);
        when(useCase.purgeDue()).thenThrow(new IllegalStateException("DELETE /internal/v1/reporters/" + MEMBER_ID));
        when(useCase.cleanUpCompleted()).thenThrow(new IllegalStateException("cleanup " + MEMBER_ID));
        ReportPurgeScheduler scheduler = new ReportPurgeScheduler(useCase);

        assertThatCode(scheduler::purgeDue).doesNotThrowAnyException();
        assertThatCode(scheduler::cleanUpCompleted).doesNotThrowAnyException();

        assertThat(output.getAll())
            .contains("report purge run aborted exception=IllegalStateException")
            .contains("report purge cleanup aborted exception=IllegalStateException")
            .doesNotContain(String.valueOf(MEMBER_ID));
    }

    @EnableConfigurationProperties(ReportPurgeProperties.class)
    static class PropertiesConfig {
    }
}
