package com.sneezecast.domainlayer.member.adapter.in.scheduler;

import com.sneezecast.domainlayer.member.application.port.in.ReportPurgeUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 원시 보고 파기 재시도 · 완료 행 정리 스케줄 (entity-design §1-5). {@code auth.report-purge.scheduler-enabled} 가 {@code true}(대소문자 무시)일 때만
 * 빈이 생긴다 — 기본은 꺼짐이고 배포(compose)가 켠다. 테스트 컨텍스트가 Eureka 없이 surveillance 를 불러 테스트 DB 에 실패 기록을 남기지 않게 한다.
 * {@code @EnableScheduling} 은 {@code AuthServiceSchedulingConfig} 가 같은 조건으로 건다.
 *
 * <p>두 메서드는 최상위에서 예외를 잡아 클래스 단순 이름만 남긴다. 잡지 않으면 Spring 기본 오류 처리기가 스택트레이스를 찍는데, 그 안의 메시지에 요청
 * URL(회원 ID)이 실릴 수 있다. 실패한 회차는 다음 주기에 다시 돈다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "auth.report-purge.scheduler-enabled", havingValue = "true")
public class ReportPurgeScheduler {

    private final ReportPurgeUseCase reportPurgeUseCase;

    /** 한 회차가 끝난 뒤 {@code fixed-delay} 만큼 쉬고 다음 회차를 돈다 — 회차가 길어져도 겹치지 않는다. */
    @Scheduled(initialDelayString = "${auth.report-purge.initial-delay}", fixedDelayString = "${auth.report-purge.fixed-delay}")
    public void purgeDue() {
        try {
            reportPurgeUseCase.purgeDue();
        } catch (RuntimeException exception) {
            log.error("report purge run aborted exception={}", exception.getClass().getSimpleName());
        }
    }

    /** 보관 기간이 지난 완료 행 정리. 시각은 배포 시간대와 상관없이 KST 로 읽는다. */
    @Scheduled(cron = "${auth.report-purge.cleanup-cron}", zone = "Asia/Seoul")
    public void cleanUpCompleted() {
        try {
            reportPurgeUseCase.cleanUpCompleted();
        } catch (RuntimeException exception) {
            log.error("report purge cleanup aborted exception={}", exception.getClass().getSimpleName());
        }
    }
}
