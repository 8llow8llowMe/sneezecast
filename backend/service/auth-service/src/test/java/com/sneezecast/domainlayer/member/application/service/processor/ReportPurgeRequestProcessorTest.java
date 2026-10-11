package com.sneezecast.domainlayer.member.application.service.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sneezecast.domainlayer.member.application.port.out.ReportPurgeRequestRepositoryPort;
import com.sneezecast.domainlayer.member.domain.enums.PurgeReason;
import com.sneezecast.domainlayer.member.domain.model.ReportPurgeRequest;
import com.sneezecast.persistence.util.SnowflakeIdGenerator;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ReportPurgeRequestProcessorTest {

    private static final long MEMBER_ID = 42L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 11, 9, 30);

    private final ReportPurgeRequestRepositoryPort port = mock(ReportPurgeRequestRepositoryPort.class);
    private final ReportPurgeRequestProcessor processor = new ReportPurgeRequestProcessor(port, new SnowflakeIdGenerator(0, 0));

    @Test
    @DisplayName("탈퇴 요청은 WITHDRAWAL 사유의 미완료 요청만 본다 — 철회 파기가 미완료여도 WITHDRAWAL 행을 남긴다(사유 무관 판정을 부르지 않는다)")
    void withdrawalIsCreatedEvenWithIncompleteConsentPurge() {
        when(port.existsIncompleteByMemberId(MEMBER_ID)).thenReturn(true);
        when(port.existsIncompleteByMemberIdAndReason(MEMBER_ID, PurgeReason.WITHDRAWAL)).thenReturn(false);

        assertThat(processor.requestWithdrawalIfAbsent(MEMBER_ID, NOW)).isTrue();

        ArgumentCaptor<ReportPurgeRequest> saved = ArgumentCaptor.forClass(ReportPurgeRequest.class);
        verify(port).save(saved.capture());
        assertThat(saved.getValue().memberId()).isEqualTo(MEMBER_ID);
        assertThat(saved.getValue().reason()).isEqualTo(PurgeReason.WITHDRAWAL);
        assertThat(saved.getValue().requestedAt()).isEqualTo(NOW);
        assertThat(saved.getValue().attemptCount()).isZero();
        assertThat(saved.getValue().completedAt()).isNull();
        verify(port, never()).existsIncompleteByMemberId(anyLong());
    }

    @Test
    @DisplayName("미완료 WITHDRAWAL 요청이 이미 있으면 둘째 행을 남기지 않는다")
    void secondWithdrawalRequestIsSkipped() {
        when(port.existsIncompleteByMemberIdAndReason(MEMBER_ID, PurgeReason.WITHDRAWAL)).thenReturn(true);

        assertThat(processor.requestWithdrawalIfAbsent(MEMBER_ID, NOW)).isFalse();
        verify(port, never()).save(any());
    }

    @Test
    @DisplayName("철회 요청(requestIfAbsent)은 그대로 사유를 보지 않는다 — 미완료 요청이 하나라도 있으면 남기지 않는다")
    void consentWithdrawalKeepsReasonAgnosticCheck() {
        when(port.existsIncompleteByMemberId(MEMBER_ID)).thenReturn(true);

        assertThat(processor.requestIfAbsent(MEMBER_ID, PurgeReason.HEALTH_CONSENT_WITHDRAWN, NOW)).isFalse();
        verify(port, never()).save(any());
        verify(port, never()).existsIncompleteByMemberIdAndReason(anyLong(), any());
    }
}
