package com.sneezecast.global.config;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.boot.task.ThreadPoolTaskExecutorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 비동기 실행 설정.
 *
 * <p>Executor 빈을 하나라도 직접 만들면 Boot 의 기본 {@code applicationTaskExecutor} 가 만들어지지 않는다. 그러면 한정자 없는
 * {@code @Async} 와 MVC 비동기 요청이 조용히 메일 executor 를 같이 쓰거나 스레드를 매번 만드는 executor 로 떨어진다. 그래서 기본
 * executor 를 같은 이름으로 직접 두고({@code spring.task.execution.*} 설정을 그대로 따른다) {@link AsyncConfigurer} 로 {@code @Async}
 * 기본값에 연결한다. 메일 발송은 {@code @Async("authMailTaskExecutor")} 로 전용 풀을 명시한다.
 */
@Slf4j
@Configuration
@EnableAsync
@RequiredArgsConstructor
public class AuthServiceAsyncConfig implements AsyncConfigurer {

    static final String MAIL_THREAD_NAME_PREFIX = "auth-mail-worker-";

    private final ThreadPoolTaskExecutorBuilder threadPoolTaskExecutorBuilder;

    /** Boot 기본 executor 를 대신한다. 이름이 같아 MVC 비동기 처리 등 이 이름을 찾는 곳이 그대로 쓴다. */
    @Bean(name = TaskExecutionAutoConfiguration.APPLICATION_TASK_EXECUTOR_BEAN_NAME)
    public ThreadPoolTaskExecutor applicationTaskExecutor() {
        return threadPoolTaskExecutorBuilder.build();
    }

    @Override
    public Executor getAsyncExecutor() {
        return applicationTaskExecutor();
    }

    /**
     * 인증 메일 발송 전용 executor. SMTP 왕복(단계마다 timeout 5초)이 요청 스레드를 붙잡지 않게 한다.
     *
     * <p>큐가 차면 그 메일을 로그만 남기고 버린다 — 메일 실패는 로그만 남긴다는 원칙과 같고, 기본 정책(AbortPolicy)처럼 예외를 던지면
     * 쿨다운 · IP 카운터 · 코드는 이미 소비됐는데 요청은 봉투 없는 500 으로 끝난다. 사용자는 메일이 오지 않으면 쿨다운 뒤 다시 요청한다.
     */
    @Bean(name = "authMailTaskExecutor")
    public ThreadPoolTaskExecutor authMailTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix(MAIL_THREAD_NAME_PREFIX);
        executor.setRejectedExecutionHandler(discardWithLog());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(15);
        return executor;
    }

    /** 거부된 작업을 버리고 로그만 남긴다. 작업 안의 수신자 · 코드는 남기지 않는다. */
    static RejectedExecutionHandler discardWithLog() {
        return (task, pool) -> log.error("auth mail task rejected, dropped queueSize={} activeCount={}", pool.getQueue().size(), pool.getActiveCount());
    }
}
