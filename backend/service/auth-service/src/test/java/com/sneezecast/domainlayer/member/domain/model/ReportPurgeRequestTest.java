package com.sneezecast.domainlayer.member.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.sneezecast.domainlayer.member.domain.enums.PurgeReason;
import java.time.Duration;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReportPurgeRequestTest {

    private static final LocalDateTime REQUESTED_AT = LocalDateTime.of(2026, 10, 8, 9, 0);
    private static final Duration SETTLE_WINDOW = Duration.ofMinutes(20);

    private final ReportPurgeRequest request = ReportPurgeRequest.builder()
        .id(1L).memberId(7350912846153L).reason(PurgeReason.HEALTH_CONSENT_WITHDRAWN).requestedAt(REQUESTED_AT).build();

    @Test
    @DisplayName("호출 시작이 요청 시각 + 대기 시간과 정확히 같으면 완료다")
    void completesExactlyAtBoundary() {
        assertThat(request.isCompletedBy(REQUESTED_AT.plus(SETTLE_WINDOW), SETTLE_WINDOW)).isTrue();
    }

    @Test
    @DisplayName("경계 1ns 전에 시작한 호출은 성공해도 완료가 아니다 — 남은 access token 으로 뒤늦게 들어올 보고를 지운다고 보장하지 못한다")
    void notCompletedJustBeforeBoundary() {
        assertThat(request.isCompletedBy(REQUESTED_AT.plus(SETTLE_WINDOW).minusNanos(1), SETTLE_WINDOW)).isFalse();
    }

    @Test
    @DisplayName("경계 뒤에 시작한 호출은 완료다 — 스케줄러가 오래 멈췄다면 1차 호출이 곧 완료다")
    void completesAfterBoundary() {
        assertThat(request.isCompletedBy(REQUESTED_AT.plusHours(3), SETTLE_WINDOW)).isTrue();
    }

    @Test
    @DisplayName("toString 에 회원 ID 가 없다 — 요청 ID · 사유 · 시도 횟수 · 완료 여부만")
    void toStringOmitsMemberId() {
        assertThat(request.toString())
            .isEqualTo("ReportPurgeRequest[id=1, reason=HEALTH_CONSENT_WITHDRAWN, attemptCount=0, completed=false]")
            .doesNotContain("7350912846153");
    }
}
