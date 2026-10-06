package com.sneezecast.domainlayer.member.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sneezecast.domainlayer.auth.application.service.support.ReportScopePolicy;
import com.sneezecast.domainlayer.member.adapter.in.web.dto.response.MemberConsentStatusResponse;
import com.sneezecast.domainlayer.member.adapter.in.web.dto.response.MemberMyInfoResponse;
import com.sneezecast.domainlayer.member.adapter.out.auth.MemberReportScopeAdapter;
import com.sneezecast.domainlayer.member.application.exception.MemberErrorCode;
import com.sneezecast.domainlayer.member.application.exception.MemberException;
import com.sneezecast.domainlayer.member.application.info.MemberConsentStatusInfo;
import com.sneezecast.domainlayer.member.application.port.in.MemberConsentWithdrawResult;
import com.sneezecast.domainlayer.member.application.port.out.MemberPasswordAttemptPort;
import com.sneezecast.domainlayer.member.application.port.out.MemberRepositoryPort;
import com.sneezecast.domainlayer.member.application.port.out.MemberSessionRevokePort;
import com.sneezecast.domainlayer.member.application.service.presenter.MemberPresenter;
import com.sneezecast.domainlayer.member.application.service.processor.MemberCommandProcessor;
import com.sneezecast.domainlayer.member.application.service.processor.MemberConsentProcessor;
import com.sneezecast.domainlayer.member.application.service.processor.MemberPasswordProcessor;
import com.sneezecast.domainlayer.member.application.service.processor.MemberQueryProcessor;
import com.sneezecast.domainlayer.member.domain.enums.ConsentType;
import com.sneezecast.domainlayer.member.domain.enums.MemberStatus;
import com.sneezecast.domainlayer.member.domain.enums.OAuthProvider;
import com.sneezecast.domainlayer.member.domain.model.Member;
import com.sneezecast.global.properties.LoginAttemptProperties;
import com.sneezecast.security.common.enums.SecurityRole;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 내 정보 · 비밀번호 변경 · 설정의 오케스트레이션. 조회 · 비밀번호 규칙 처리기는 실제 객체이고, 저장소 · 세션 폐기 · DB 쓰기는 mock 이다.
 */
class MemberWebFacadeTest {

    private static final String SESSION_ID = "3f2a9c11-0e4b-4a1f-9c3d-0b8e2f7a5d61";
    private static final String PASSWORD = "P@ssw0rd!";
    private static final String NEW_PASSWORD = "Sneeze2026!";

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);
    private MemberRepositoryPort memberRepositoryPort;
    private MemberConsentProcessor memberConsentProcessor;
    private MemberCommandProcessor memberCommandProcessor;
    private MemberSessionRevokePort memberSessionRevokePort;
    private MemberWebFacade facade;

    @BeforeEach
    void setUp() {
        memberRepositoryPort = mock(MemberRepositoryPort.class);
        memberConsentProcessor = mock(MemberConsentProcessor.class);
        memberCommandProcessor = mock(MemberCommandProcessor.class);
        memberSessionRevokePort = mock(MemberSessionRevokePort.class);
        MemberPasswordAttemptPort attemptPort = mock(MemberPasswordAttemptPort.class);
        when(attemptPort.increaseFailureCount(anyLong(), any())).thenReturn(1L);
        when(memberConsentProcessor.currentStatus(anyLong())).thenReturn(MemberConsentStatusInfo.builder().healthInfoAgreed(true).build());
        MemberReportScopeAdapter reportScopeAdapter = new MemberReportScopeAdapter(new ReportScopePolicy());
        MemberQueryProcessor queryProcessor = new MemberQueryProcessor(memberRepositoryPort, memberConsentProcessor, reportScopeAdapter);
        facade = new MemberWebFacade(queryProcessor, memberCommandProcessor,
            new MemberPasswordProcessor(passwordEncoder, attemptPort, new LoginAttemptProperties(5, Duration.ofMinutes(10), 30, Duration.ofHours(1))),
            memberConsentProcessor, reportScopeAdapter, memberSessionRevokePort, passwordEncoder, new MemberPresenter());
    }

    @Test
    @DisplayName("동의는 회원 상태를 확인하고 처리기에 넘긴 뒤, 동의 뒤의 상태를 토큰과 같은 계산으로 돌려준다")
    void agreeConsentReturnsStatus() {
        givenMember(emailMember(MemberStatus.ACTIVE));
        when(memberConsentProcessor.currentStatus(42L)).thenReturn(MemberConsentStatusInfo.builder().healthInfoAgreed(true).build());

        MemberConsentStatusResponse response = facade.agreeConsent(42L, ConsentType.SENSITIVE_HEALTH_INFO, "2026-10-01");

        verify(memberConsentProcessor).agree(42L, ConsentType.SENSITIVE_HEALTH_INFO, "2026-10-01");
        assertThat(response.pendingConsents()).isEmpty();
        assertThat(response.healthInfoAgreed()).isTrue();
        assertThat(response.reportWritable()).isTrue();
        assertThat(response.purgePending()).isFalse();
    }

    @Test
    @DisplayName("미완료 파기가 있으면 건강정보에 다시 동의해도 reportWritable false · purgePending true 다")
    void agreeConsentWithPendingPurgeIsNotWritable() {
        givenMember(emailMember(MemberStatus.ACTIVE));
        when(memberConsentProcessor.currentStatus(42L)).thenReturn(MemberConsentStatusInfo.builder().healthInfoAgreed(true).purgePending(true).build());

        MemberConsentStatusResponse response = facade.agreeConsent(42L, ConsentType.SENSITIVE_HEALTH_INFO, "2026-10-01");

        assertThat(response.healthInfoAgreed()).isTrue();
        assertThat(response.reportWritable()).isFalse();
        assertThat(response.purgePending()).isTrue();
    }

    @Test
    @DisplayName("미완료 파기가 있으면 내 정보의 reportWritable 도 false 다 — 토큰 · 동의 응답과 같은 계산")
    void myInfoWithPendingPurgeIsNotWritable() {
        givenMember(emailMember(MemberStatus.ACTIVE));
        when(memberConsentProcessor.currentStatus(42L)).thenReturn(MemberConsentStatusInfo.builder().healthInfoAgreed(true).purgePending(true).build());

        assertThat(facade.getMyInfo(42L).reportWritable()).isFalse();
    }

    @Test
    @DisplayName("탈퇴 · 정지 회원은 동의 · 철회할 수 없다 — 처리기를 부르지 않는다")
    void consentRequiresActiveMember() {
        givenMember(emailMember(MemberStatus.SUSPENDED));

        assertThat(failure(() -> facade.agreeConsent(42L, ConsentType.SENSITIVE_HEALTH_INFO, "v"))).isEqualTo(MemberErrorCode.SUSPENDED_MEMBER);
        assertThat(failure(() -> facade.withdrawConsent(42L, ConsentType.SENSITIVE_HEALTH_INFO, "jti", null))).isEqualTo(MemberErrorCode.SUSPENDED_MEMBER);
        verify(memberConsentProcessor, never()).agree(anyLong(), any(), any());
        verify(memberConsentProcessor, never()).withdraw(anyLong(), any());
    }

    @Test
    @DisplayName("철회하면 커밋(처리기 반환) 뒤에 요청 access 를 포함해 모든 기기를 로그아웃시키고, 로그아웃했음을 알린다")
    void withdrawRevokesAllSessionsAfterCommit() {
        givenMember(emailMember(MemberStatus.ACTIVE));
        Instant expiresAt = Instant.now().plusSeconds(600);
        when(memberConsentProcessor.withdraw(42L, ConsentType.SENSITIVE_HEALTH_INFO)).thenReturn(true);
        when(memberConsentProcessor.currentStatus(42L)).thenReturn(MemberConsentStatusInfo.builder().purgePending(true).build());

        MemberConsentWithdrawResult result = facade.withdrawConsent(42L, ConsentType.SENSITIVE_HEALTH_INFO, "access-jti", expiresAt);

        InOrder order = inOrder(memberConsentProcessor, memberSessionRevokePort);
        order.verify(memberConsentProcessor).withdraw(42L, ConsentType.SENSITIVE_HEALTH_INFO);
        order.verify(memberSessionRevokePort).revokeAllSessions(42L, "access-jti", expiresAt);
        assertThat(result.loggedOut()).isTrue();
        assertThat(result.response().healthInfoAgreed()).isFalse();
        assertThat(result.response().reportWritable()).isFalse();
        assertThat(result.response().purgePending()).isTrue();
    }

    @Test
    @DisplayName("철회할 동의가 없으면(멱등) 세션을 건드리지 않고 로그아웃하지 않았다고 알린다")
    void withdrawWithoutConsentDoesNothing() {
        givenMember(emailMember(MemberStatus.ACTIVE));
        when(memberConsentProcessor.withdraw(42L, ConsentType.SENSITIVE_HEALTH_INFO)).thenReturn(false);

        MemberConsentWithdrawResult result = facade.withdrawConsent(42L, ConsentType.SENSITIVE_HEALTH_INFO, "access-jti", null);

        assertThat(result.loggedOut()).isFalse();
        verify(memberSessionRevokePort, never()).revokeAllSessions(anyLong(), any(), any());
    }

    @Test
    @DisplayName("커밋 뒤 로그아웃이 실패해도(MEMBER_009) 철회는 성공이다 — 철회 · 파기 요청은 이미 커밋됐다")
    void withdrawToleratesRevokeFailure() {
        givenMember(emailMember(MemberStatus.ACTIVE));
        when(memberConsentProcessor.withdraw(42L, ConsentType.SENSITIVE_HEALTH_INFO)).thenReturn(true);
        doThrow(new MemberException(MemberErrorCode.SESSION_REVOKE_UNAVAILABLE)).when(memberSessionRevokePort).revokeAllSessions(anyLong(), any(), any());

        MemberConsentWithdrawResult result = facade.withdrawConsent(42L, ConsentType.SENSITIVE_HEALTH_INFO, "access-jti", null);

        assertThat(result.loggedOut()).as("쿠키는 지운다").isTrue();
    }

    @Test
    @DisplayName("철회할 수 없는 항목(MEMBER_012)이면 세션을 건드리지 않는다")
    void withdrawRejectionKeepsSessions() {
        givenMember(emailMember(MemberStatus.ACTIVE));
        when(memberConsentProcessor.withdraw(42L, ConsentType.TERMS_OF_SERVICE)).thenThrow(new MemberException(MemberErrorCode.CONSENT_NOT_WITHDRAWABLE));

        assertThat(failure(() -> facade.withdrawConsent(42L, ConsentType.TERMS_OF_SERVICE, "access-jti", null)))
            .isEqualTo(MemberErrorCode.CONSENT_NOT_WITHDRAWABLE);
        verify(memberSessionRevokePort, never()).revokeAllSessions(anyLong(), any(), any());
    }

    @Test
    @DisplayName("이메일 가입 회원의 내 정보 — provider EMAIL · hasPassword true, 동의가 모두 유효하면 reportWritable true · pendingConsents 빈 목록")
    void myInfoOfEmailMember() {
        givenMember(emailMember(MemberStatus.ACTIVE));

        MemberMyInfoResponse response = facade.getMyInfo(42L);

        assertThat(response.memberId()).isEqualTo("42");
        assertThat(response.email()).isEqualTo("user@example.com");
        assertThat(response.nickname()).isEqualTo("재채기탐정");
        assertThat(response.provider()).isEqualTo("EMAIL");
        assertThat(response.hasPassword()).isTrue();
        assertThat(response.role()).isEqualTo("USER");
        assertThat(response.pendingConsents()).isEmpty();
        assertThat(response.reportWritable()).isTrue();
    }

    @Test
    @DisplayName("카카오 가입 회원 — provider KAKAO · hasPassword false. 재동의 대기가 있으면 건강정보 동의가 있어도 reportWritable false (토큰과 같은 계산)")
    void myInfoOfSocialMemberWithPendingConsent() {
        givenMember(socialMember());
        when(memberConsentProcessor.currentStatus(42L)).thenReturn(MemberConsentStatusInfo.builder()
            .pendingRequiredConsents(List.of(ConsentType.PRIVACY_POLICY)).healthInfoAgreed(true).build());

        MemberMyInfoResponse response = facade.getMyInfo(42L);

        assertThat(response.provider()).isEqualTo("KAKAO");
        assertThat(response.hasPassword()).isFalse();
        assertThat(response.pendingConsents()).containsExactly("PRIVACY_POLICY");
        assertThat(response.reportWritable()).isFalse();
    }

    @Test
    @DisplayName("회원 행이 없으면 MEMBER_004(404), 탈퇴 MEMBER_002 · 정지 MEMBER_003 이다")
    void myInfoRejectsMissingOrInactiveMember() {
        when(memberRepositoryPort.findById(42L)).thenReturn(Optional.empty());
        assertThat(failure(() -> facade.getMyInfo(42L))).isEqualTo(MemberErrorCode.MEMBER_NOT_FOUND);
        assertThat(MemberErrorCode.MEMBER_NOT_FOUND.getHttpStatus().value()).isEqualTo(404);

        givenMember(emailMember(MemberStatus.WITHDRAWN));
        assertThat(failure(() -> facade.getMyInfo(42L))).isEqualTo(MemberErrorCode.WITHDRAWN_MEMBER);
        givenMember(emailMember(MemberStatus.SUSPENDED));
        assertThat(failure(() -> facade.updateMyInfo(42L, "새닉네임"))).isEqualTo(MemberErrorCode.SUSPENDED_MEMBER);
        verify(memberCommandProcessor, never()).changeNickname(anyLong(), anyString());
    }

    @Test
    @DisplayName("닉네임 수정은 앞뒤 공백을 지워 넘기고, 바뀐 회원으로 내 정보를 돌려준다")
    void updateNicknameStripsAndReturnsUpdated() {
        givenMember(emailMember(MemberStatus.ACTIVE));
        when(memberCommandProcessor.changeNickname(42L, "새닉네임")).thenReturn(Member.builder().id(42L).email("user@example.com").password("hash")
            .nickname("새닉네임").role(SecurityRole.USER).status(MemberStatus.ACTIVE).build());

        MemberMyInfoResponse response = facade.updateMyInfo(42L, "  새닉네임 ");

        verify(memberCommandProcessor).changeNickname(42L, "새닉네임");
        assertThat(response.nickname()).isEqualTo("새닉네임");
    }

    @Test
    @DisplayName("비밀번호 변경 성공 — 해시(트랜잭션 밖) → 지금 기기를 뺀 세션 폐기 → 저장(커밋) → 같은 범위로 2차 폐기 순서이고, 저장 해시는 새 비밀번호다")
    void changePasswordRevokesOtherSessionsBeforeAndAfterSaving() {
        givenMember(emailMember(MemberStatus.ACTIVE));

        facade.changePassword(42L, SESSION_ID, PASSWORD, NEW_PASSWORD);

        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        InOrder order = inOrder(memberSessionRevokePort, memberCommandProcessor);
        order.verify(memberSessionRevokePort).revokeOtherSessions(42L, SESSION_ID);
        order.verify(memberCommandProcessor).changePassword(eq(42L), hash.capture());
        order.verify(memberSessionRevokePort).revokeOtherSessions(42L, SESSION_ID);
        order.verifyNoMoreInteractions();
        assertThat(passwordEncoder.matches(NEW_PASSWORD, hash.getValue())).isTrue();
    }

    @Test
    @DisplayName("저장 뒤 2차 폐기가 실패해도(MEMBER_009) 변경은 성공이다 — 비밀번호는 이미 바뀌었다")
    void postCommitRevokeFailureIsTolerated() {
        givenMember(emailMember(MemberStatus.ACTIVE));
        doNothing().doThrow(new MemberException(MemberErrorCode.SESSION_REVOKE_UNAVAILABLE)).when(memberSessionRevokePort)
            .revokeOtherSessions(42L, SESSION_ID);

        assertThatCode(() -> facade.changePassword(42L, SESSION_ID, PASSWORD, NEW_PASSWORD)).doesNotThrowAnyException();

        verify(memberSessionRevokePort, times(2)).revokeOtherSessions(42L, SESSION_ID);
        verify(memberCommandProcessor).changePassword(eq(42L), anyString());
    }

    @Test
    @DisplayName("세션 ID 가 없는 토큰이면 남길 기기를 몰라 모든 세션을 끊는다(null 로 넘긴다) — 2차 폐기도 같은 범위다")
    void changePasswordWithoutSessionRevokesAll() {
        givenMember(emailMember(MemberStatus.ACTIVE));

        facade.changePassword(42L, null, PASSWORD, NEW_PASSWORD);

        verify(memberSessionRevokePort, times(2)).revokeOtherSessions(42L, null);
    }

    @Test
    @DisplayName("소셜 계정의 변경은 MEMBER_007(409), 현재 비밀번호가 틀리면 MEMBER_005(400) — 어느 쪽도 세션 · 비밀번호를 건드리지 않는다")
    void changePasswordRejections() {
        givenMember(socialMember());
        assertThat(failure(() -> facade.changePassword(42L, SESSION_ID, PASSWORD, NEW_PASSWORD))).isEqualTo(MemberErrorCode.PASSWORD_NOT_SET);

        givenMember(emailMember(MemberStatus.ACTIVE));
        assertThat(failure(() -> facade.changePassword(42L, SESSION_ID, "wrong-password1", NEW_PASSWORD)))
            .isEqualTo(MemberErrorCode.CURRENT_PASSWORD_MISMATCH);

        verify(memberSessionRevokePort, never()).revokeOtherSessions(anyLong(), any());
        verify(memberCommandProcessor, never()).changePassword(anyLong(), anyString());
    }

    @Test
    @DisplayName("다른 기기 세션 폐기가 실패하면(MEMBER_009 503) 비밀번호를 바꾸지 않는다")
    void sessionRevokeFailureKeepsOldPassword() {
        givenMember(emailMember(MemberStatus.ACTIVE));
        doThrow(new MemberException(MemberErrorCode.SESSION_REVOKE_UNAVAILABLE)).when(memberSessionRevokePort).revokeOtherSessions(42L, SESSION_ID);

        assertThat(failure(() -> facade.changePassword(42L, SESSION_ID, PASSWORD, NEW_PASSWORD))).isEqualTo(MemberErrorCode.SESSION_REVOKE_UNAVAILABLE);
        assertThat(MemberErrorCode.SESSION_REVOKE_UNAVAILABLE.getHttpStatus().value()).isEqualTo(503);

        verify(memberCommandProcessor, never()).changePassword(anyLong(), anyString());
    }

    private void givenMember(Member member) {
        when(memberRepositoryPort.findById(42L)).thenReturn(Optional.of(member));
    }

    private Member emailMember(MemberStatus status) {
        return Member.builder().id(42L).email("user@example.com").password(passwordEncoder.encode(PASSWORD)).nickname("재채기탐정")
            .role(SecurityRole.USER).provider(null).status(status).build();
    }

    private static Member socialMember() {
        return Member.builder().id(42L).email("kakao@example.com").password(null).nickname("재채기탐정").role(SecurityRole.USER)
            .provider(OAuthProvider.KAKAO).status(MemberStatus.ACTIVE).build();
    }

    private static MemberErrorCode failure(Runnable call) {
        return catchThrowableOfType(MemberException.class, call::run).getErrorCode();
    }
}
