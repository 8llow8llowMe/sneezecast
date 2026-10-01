package com.sneezecast.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.task.ThreadPoolTaskExecutorBuilder;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

class AuthServiceAsyncConfigTest {

    @Test
    @DisplayName("메일 executor 가 꽉 차면 작업을 버리고 예외를 던지지 않는다 — 호출 요청이 봉투 없는 500 으로 끝나지 않는다")
    void saturatedExecutorDropsTaskWithoutThrowing() throws InterruptedException {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(0);
        executor.setRejectedExecutionHandler(AuthServiceAsyncConfig.discardWithLog());
        executor.initialize();
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean droppedRan = new AtomicBoolean(false);
        try {
            executor.execute(() -> awaitQuietly(release));

            // @Async 는 submit 경로를 탄다. execute 도 함께 본다.
            assertThatCode(() -> executor.submit(() -> droppedRan.set(true))).doesNotThrowAnyException();
            assertThatCode(() -> executor.execute(() -> droppedRan.set(true))).doesNotThrowAnyException();
        } finally {
            release.countDown();
            executor.shutdown();
            executor.getThreadPoolExecutor().awaitTermination(5, TimeUnit.SECONDS);
        }
        assertThat(droppedRan).isFalse();
    }

    @Test
    @DisplayName("메일 executor 빈은 버리는 거부 정책과 전용 스레드 이름을 쓴다 — 기본 AbortPolicy 가 아니다")
    void mailExecutorUsesDiscardPolicy() {
        ThreadPoolTaskExecutor executor = new AuthServiceAsyncConfig(new ThreadPoolTaskExecutorBuilder()).authMailTaskExecutor();
        executor.initialize();
        try {
            assertThat(executor.getThreadPoolExecutor().getRejectedExecutionHandler()).isNotInstanceOf(ThreadPoolExecutor.AbortPolicy.class);
            assertThat(executor.getThreadNamePrefix()).isEqualTo(AuthServiceAsyncConfig.MAIL_THREAD_NAME_PREFIX);
        } finally {
            executor.shutdown();
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
