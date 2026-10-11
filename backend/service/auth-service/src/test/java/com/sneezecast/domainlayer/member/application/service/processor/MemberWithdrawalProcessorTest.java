package com.sneezecast.domainlayer.member.application.service.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.sneezecast.domainlayer.member.application.exception.MemberErrorCode;
import com.sneezecast.domainlayer.member.application.exception.MemberException;
import com.sneezecast.domainlayer.member.application.port.out.MemberNotificationSettingRepositoryPort;
import com.sneezecast.domainlayer.member.application.port.out.MemberRegionCleanupPort;
import com.sneezecast.domainlayer.member.application.port.out.MemberRepositoryPort;
import com.sneezecast.domainlayer.member.application.service.support.WithdrawnEmailHasher;
import com.sneezecast.domainlayer.member.domain.enums.MemberStatus;
import com.sneezecast.domainlayer.member.domain.model.Member;
import com.sneezecast.global.properties.WithdrawnEmailProperties;
import com.sneezecast.security.common.enums.SecurityRole;
import com.sneezecast.storage.client.ObjectStorageClient;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

/**
 * 탈퇴 처리기의 순서와 값. 상태 판정({@link MemberQueryProcessor})과 해셔는 실제 객체이고 저장소 · 정리 포트는 mock 이다. 트랜잭션 원자성 · 동시성은
 * {@code MemberWithdrawalTransactionTest} 가 본다.
 */
class MemberWithdrawalProcessorTest {

    private static final long MEMBER_ID = 42L;

    private final WithdrawnEmailHasher hasher = new WithdrawnEmailHasher(new WithdrawnEmailProperties("withdrawn-email-pepper-0123456789abcdef"));
    private MemberRepositoryPort memberRepositoryPort;
    private ReportPurgeRequestProcessor reportPurgeRequestProcessor;
    private MemberRegionCleanupPort memberRegionCleanupPort;
    private MemberNotificationSettingRepositoryPort notificationSettingPort;
    private ObjectStorageClient objectStorageClient;
    private MemberWithdrawalProcessor processor;

    @BeforeEach
    void setUp() {
        memberRepositoryPort = mock(MemberRepositoryPort.class);
        reportPurgeRequestProcessor = mock(ReportPurgeRequestProcessor.class);
        memberRegionCleanupPort = mock(MemberRegionCleanupPort.class);
        notificationSettingPort = mock(MemberNotificationSettingRepositoryPort.class);
        objectStorageClient = mock(ObjectStorageClient.class);
        MemberQueryProcessor queryProcessor = new MemberQueryProcessor(memberRepositoryPort, mock(MemberConsentProcessor.class), status -> true, objectStorageClient);
        processor = new MemberWithdrawalProcessor(queryProcessor, memberRepositoryPort, hasher, reportPurgeRequestProcessor, memberRegionCleanupPort,
            notificationSettingPort);
    }

    @Test
    @DisplayName("업로드한 프로필 이미지가 있으면 잠금 읽기로 본 key 를 돌려주고 지우지는 않는다 — 삭제는 커밋 뒤 Facade 몫이다. 없으면 empty")
    void returnsProfileImageKeyWithoutDeleting() {
        when(memberRepositoryPort.findByIdForUpdate(MEMBER_ID)).thenReturn(Optional.of(Member.builder().id(MEMBER_ID).email("user@example.com")
            .nickname("재채기탐정").profileImageKey("members/profiles/42/2026/10/a.png").role(SecurityRole.USER).status(MemberStatus.ACTIVE).build()));
        when(memberRepositoryPort.withdraw(anyLong(), anyString(), anyString(), any())).thenAnswer(invocation -> Optional.of(member(MemberStatus.WITHDRAWN)));

        assertThat(processor.withdraw(MEMBER_ID)).contains("members/profiles/42/2026/10/a.png");
        verifyNoInteractions(objectStorageClient);

        givenLockedMember(MemberStatus.ACTIVE);
        assertThat(processor.withdraw(MEMBER_ID)).isEmpty();
    }

    @Test
    @DisplayName("잠금 읽기로 상태를 본 뒤 회원 행 탈퇴(다이제스트 · 마스킹 닉네임) → WITHDRAWAL 파기 요청 → 관심 동네 → 알림 설정 순서이고, 시각은 하나다")
    void withdrawsInOrderWithOneTimestamp() {
        givenLockedMember(MemberStatus.ACTIVE);
        when(memberRepositoryPort.withdraw(anyLong(), anyString(), anyString(), any())).thenAnswer(invocation -> Optional.of(member(MemberStatus.WITHDRAWN)));
        LocalDateTime before = LocalDateTime.now();

        processor.withdraw(MEMBER_ID);

        ArgumentCaptor<LocalDateTime> withdrawnAt = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> requestedAt = ArgumentCaptor.forClass(LocalDateTime.class);
        InOrder order = inOrder(memberRepositoryPort, reportPurgeRequestProcessor, memberRegionCleanupPort, notificationSettingPort);
        order.verify(memberRepositoryPort).findByIdForUpdate(MEMBER_ID);
        order.verify(memberRepositoryPort).withdraw(eq(MEMBER_ID), eq(hasher.hash(MEMBER_ID, "user@example.com")),
            eq(MemberWithdrawalProcessor.WITHDRAWN_NICKNAME), withdrawnAt.capture());
        order.verify(reportPurgeRequestProcessor).requestWithdrawalIfAbsent(eq(MEMBER_ID), requestedAt.capture());
        order.verify(memberRegionCleanupPort).deleteInterestRegions(MEMBER_ID);
        order.verify(notificationSettingPort).deleteByMemberId(MEMBER_ID);
        assertThat(withdrawnAt.getValue()).isAfterOrEqualTo(before).isEqualTo(requestedAt.getValue());
        verify(memberRepositoryPort, never()).findById(anyLong());
    }

    @Test
    @DisplayName("이미 탈퇴(MEMBER_002) · 정지(MEMBER_003) · 회원 없음(MEMBER_004)이면 아무것도 바꾸지 않는다")
    void inactiveMemberChangesNothing() {
        givenLockedMember(MemberStatus.WITHDRAWN);
        assertThat(failure()).isEqualTo(MemberErrorCode.WITHDRAWN_MEMBER);

        givenLockedMember(MemberStatus.SUSPENDED);
        assertThat(failure()).isEqualTo(MemberErrorCode.SUSPENDED_MEMBER);

        when(memberRepositoryPort.findByIdForUpdate(MEMBER_ID)).thenReturn(Optional.empty());
        assertThat(failure()).isEqualTo(MemberErrorCode.MEMBER_NOT_FOUND);

        verify(memberRepositoryPort, never()).withdraw(anyLong(), any(), any(), any());
        verifyNoInteractions(reportPurgeRequestProcessor, memberRegionCleanupPort, notificationSettingPort);
    }

    private MemberErrorCode failure() {
        return catchThrowableOfType(MemberException.class, () -> processor.withdraw(MEMBER_ID)).getErrorCode();
    }

    private void givenLockedMember(MemberStatus status) {
        when(memberRepositoryPort.findByIdForUpdate(MEMBER_ID)).thenReturn(Optional.of(member(status)));
    }

    private static Member member(MemberStatus status) {
        return Member.builder().id(MEMBER_ID).email("user@example.com").password("$2a$10$hash").nickname("재채기탐정").role(SecurityRole.USER).status(status)
            .build();
    }
}
