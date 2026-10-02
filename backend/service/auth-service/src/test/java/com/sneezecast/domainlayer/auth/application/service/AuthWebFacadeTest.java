package com.sneezecast.domainlayer.auth.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

import com.sneezecast.domainlayer.auth.adapter.in.web.dto.response.AuthTokenResponse;
import com.sneezecast.domainlayer.auth.application.command.AuthGeneralLoginCommand;
import com.sneezecast.domainlayer.auth.application.command.AuthGeneralSignupCommand;
import com.sneezecast.domainlayer.auth.application.info.AuthCookieResult;
import com.sneezecast.domainlayer.auth.application.info.AuthTokenInfo;
import com.sneezecast.domainlayer.auth.application.service.presenter.AuthPresenter;
import com.sneezecast.domainlayer.auth.application.service.processor.AuthSessionProcessor;
import com.sneezecast.domainlayer.auth.application.service.processor.AuthTokenProcessor;
import com.sneezecast.domainlayer.auth.application.service.processor.GeneralLoginProcessor;
import com.sneezecast.domainlayer.auth.application.exception.AuthErrorCode;
import com.sneezecast.domainlayer.auth.application.exception.AuthException;
import com.sneezecast.domainlayer.auth.application.service.processor.EmailVerificationProcessor;
import com.sneezecast.domainlayer.auth.application.service.processor.GeneralSignupProcessor;
import com.sneezecast.domainlayer.auth.application.service.processor.PasswordResetProcessor;
import com.sneezecast.domainlayer.member.application.exception.MemberErrorCode;
import com.sneezecast.domainlayer.member.application.service.processor.MemberCommandProcessor;
import com.sneezecast.domainlayer.member.application.exception.MemberException;
import com.sneezecast.domainlayer.member.domain.enums.ConsentType;
import com.sneezecast.domainlayer.member.domain.enums.MemberStatus;
import com.sneezecast.domainlayer.member.domain.model.Member;
import com.sneezecast.security.common.enums.SecurityRole;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

class AuthWebFacadeTest {

    private static final String EMAIL = "user@example.com";
    private static final String PASSWORD = "P@ssw0rd!";

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);
    private EmailVerificationProcessor emailVerificationProcessor;
    private GeneralSignupProcessor generalSignupProcessor;
    private GeneralLoginProcessor generalLoginProcessor;
    private AuthTokenProcessor authTokenProcessor;
    private AuthSessionProcessor authSessionProcessor;
    private PasswordResetProcessor passwordResetProcessor;
    private MemberCommandProcessor memberCommandProcessor;
    private AuthWebFacade facade;

    @BeforeEach
    void setUp() {
        emailVerificationProcessor = mock(EmailVerificationProcessor.class);
        generalSignupProcessor = mock(GeneralSignupProcessor.class);
        generalLoginProcessor = mock(GeneralLoginProcessor.class);
        authTokenProcessor = mock(AuthTokenProcessor.class);
        authSessionProcessor = mock(AuthSessionProcessor.class);
        passwordResetProcessor = mock(PasswordResetProcessor.class);
        memberCommandProcessor = mock(MemberCommandProcessor.class);
        facade = facadeWith(passwordEncoder);
    }

    private AuthWebFacade facadeWith(PasswordEncoder encoder) {
        return new AuthWebFacade(emailVerificationProcessor, generalSignupProcessor, encoder, generalLoginProcessor, authTokenProcessor,
            authSessionProcessor, new AuthPresenter(), passwordResetProcessor, memberCommandProcessor);
    }

    @Test
    @DisplayName("재설정은 토큰 소비 → 해시(트랜잭션 밖) → 모든 세션 폐기 → 비밀번호 저장 → 로그인 잠금 해제 순서다")
    void resetPasswordRevokesAllSessionsBeforeSaving() {
        Member member = Member.builder().id(42L).email(EMAIL).role(SecurityRole.USER).status(MemberStatus.ACTIVE).build();
        when(passwordResetProcessor.consumeToken("reset-token", "203.0.113.10")).thenReturn(member);

        facade.resetPassword("reset-token", "Sneeze2026!", "203.0.113.10");

        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        InOrder order = inOrder(passwordResetProcessor, authSessionProcessor, memberCommandProcessor);
        order.verify(passwordResetProcessor).consumeToken("reset-token", "203.0.113.10");
        order.verify(authSessionProcessor).revokeAllSessions(42L);
        order.verify(memberCommandProcessor).changePassword(eq(42L), hash.capture());
        order.verify(authSessionProcessor).revokeAllSessions(42L);
        order.verify(passwordResetProcessor).releaseLoginLock(EMAIL);
        order.verifyNoMoreInteractions();
        assertThat(passwordEncoder.matches("Sneeze2026!", hash.getValue())).isTrue();
    }

    @Test
    @DisplayName("저장 뒤 2차 세션 폐기가 실패해도 재설정은 성공이고 로그인 잠금도 푼다 — 비밀번호는 이미 바뀌었다")
    void postCommitRevokeFailureIsTolerated() {
        Member member = Member.builder().id(42L).email(EMAIL).role(SecurityRole.USER).status(MemberStatus.ACTIVE).build();
        when(passwordResetProcessor.consumeToken("reset-token", "203.0.113.10")).thenReturn(member);
        doNothing().doThrow(new AuthException(AuthErrorCode.SESSION_STORE_UNAVAILABLE)).when(authSessionProcessor).revokeAllSessions(42L);

        assertThatCode(() -> facade.resetPassword("reset-token", "Sneeze2026!", "203.0.113.10")).doesNotThrowAnyException();

        verify(authSessionProcessor, times(2)).revokeAllSessions(42L);
        verify(memberCommandProcessor).changePassword(eq(42L), anyString());
        verify(passwordResetProcessor).releaseLoginLock(EMAIL);
    }

    @Test
    @DisplayName("토큰 단계(IP 상한 · 만료)에서 막히면 BCrypt 를 돌리지 않고 세션 · 비밀번호도 건드리지 않는다")
    void rejectedTokenSkipsHashing() {
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        AuthWebFacade guarded = facadeWith(encoder);
        when(passwordResetProcessor.consumeToken(anyString(), anyString())).thenThrow(new AuthException(AuthErrorCode.PASSWORD_RESET_IP_LIMITED));

        assertThatThrownBy(() -> guarded.resetPassword("reset-token", "Sneeze2026!", "203.0.113.10")).isInstanceOf(AuthException.class);

        verify(encoder, never()).encode(any());
        verify(authSessionProcessor, never()).revokeAllSessions(anyLong());
        verify(memberCommandProcessor, never()).changePassword(anyLong(), anyString());
    }

    @Test
    @DisplayName("세션 폐기가 실패하면(AUTH_017) 비밀번호를 저장하지 않고 잠금도 풀지 않는다")
    void sessionRevokeFailureKeepsOldPassword() {
        Member member = Member.builder().id(42L).email(EMAIL).role(SecurityRole.USER).status(MemberStatus.ACTIVE).build();
        when(passwordResetProcessor.consumeToken("reset-token", "203.0.113.10")).thenReturn(member);
        doThrow(new AuthException(AuthErrorCode.SESSION_STORE_UNAVAILABLE)).when(authSessionProcessor).revokeAllSessions(42L);

        assertThatThrownBy(() -> facade.resetPassword("reset-token", "Sneeze2026!", "203.0.113.10"))
            .isInstanceOfSatisfying(AuthException.class, e -> assertThat(e.getErrorCode()).isEqualTo(AuthErrorCode.SESSION_STORE_UNAVAILABLE));

        verify(memberCommandProcessor, never()).changePassword(anyLong(), anyString());
        verify(passwordResetProcessor, never()).releaseLoginLock(anyString());
    }

    @Test
    @DisplayName("재설정 코드 발송 · 확인은 이메일을 정규화해 넘기고, 확인 응답에 토큰을 싣는다")
    void resetCodeNormalizesEmail() {
        when(passwordResetProcessor.verifyCode(EMAIL, "482913", "203.0.113.10")).thenReturn("reset-token");

        facade.sendPasswordResetCode("  User@Example.COM ", "203.0.113.10");
        assertThat(facade.verifyPasswordResetCode("USER@example.com", "482913", "203.0.113.10").resetToken()).isEqualTo("reset-token");

        verify(passwordResetProcessor).sendCode(EMAIL, "203.0.113.10");
    }

    @Test
    @DisplayName("인증 확인 → 저장 → 인증 표시 소비 순서이고, 저장에는 정규화한 명령과 트랜잭션 밖에서 만든 BCrypt 해시를 넘긴다")
    void signupNormalizesHashesAndConsumesAfterSave() {
        facade.generalSignup(command("  User@Example.COM ", " 재채기탐정 "));

        ArgumentCaptor<AuthGeneralSignupCommand> saved = ArgumentCaptor.forClass(AuthGeneralSignupCommand.class);
        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        InOrder order = inOrder(emailVerificationProcessor, generalSignupProcessor);
        order.verify(emailVerificationProcessor).requireVerified(EMAIL);
        order.verify(generalSignupProcessor).signup(saved.capture(), hash.capture());
        order.verify(emailVerificationProcessor).consumeVerified(EMAIL);

        assertThat(saved.getValue().email()).isEqualTo(EMAIL);
        assertThat(saved.getValue().nickname()).isEqualTo("재채기탐정");
        assertThat(hash.getValue()).isNotEqualTo(PASSWORD).startsWith("$2");
        assertThat(passwordEncoder.matches(PASSWORD, hash.getValue())).isTrue();
    }

    @Test
    @DisplayName("미인증 이메일이면 AUTH_007 이고 저장을 시도하지 않는다")
    void unverifiedEmailStopsBeforeSave() {
        doThrow(new AuthException(AuthErrorCode.EMAIL_NOT_VERIFIED)).when(emailVerificationProcessor).requireVerified(EMAIL);

        assertThatThrownBy(() -> facade.generalSignup(command(EMAIL, "재채기탐정"))).isInstanceOf(AuthException.class);

        verify(generalSignupProcessor, never()).signup(any(), any());
        verify(emailVerificationProcessor, never()).consumeVerified(anyString());
    }

    @Test
    @DisplayName("저장이 실패하면 인증 표시를 남겨 둔다 — 인증을 다시 하지 않고 가입만 다시 시도할 수 있다")
    void failedSaveKeepsVerification() {
        doThrow(new MemberException(MemberErrorCode.EXIST_MEMBER_EMAIL)).when(generalSignupProcessor).signup(any(), any());

        assertThatThrownBy(() -> facade.generalSignup(command(EMAIL, "재채기탐정"))).isInstanceOf(MemberException.class);

        verify(emailVerificationProcessor, never()).consumeVerified(anyString());
    }

    @Test
    @DisplayName("인증코드 발송 · 검증은 이메일을 소문자 · trim 으로 정규화해 넘긴다 — 가입 때의 키와 같아야 한다")
    void emailIsNormalizedForVerification() {
        facade.sendEmailVerificationCode("  User@Example.COM ", "203.0.113.10");
        facade.verifyEmailVerificationCode("USER@example.com", "482913", "203.0.113.10");

        verify(emailVerificationProcessor).sendCode(EMAIL, "203.0.113.10");
        verify(emailVerificationProcessor).verifyCode(EMAIL, "482913", "203.0.113.10");
    }

    @Test
    @DisplayName("로그인은 정규화한 이메일로 자격을 확인하고, 응답은 ID 를 문자열로 · enum 을 이름으로 바꾸며 refresh 는 쿠키 값으로 따로 넘긴다")
    void loginNormalizesEmailAndSplitsRefreshToken() {
        Member member = Member.builder().id(1843956734582784L).email(EMAIL).role(SecurityRole.USER).status(MemberStatus.ACTIVE).build();
        when(generalLoginProcessor.authenticate(EMAIL, PASSWORD, "203.0.113.10")).thenReturn(member);
        when(authTokenProcessor.issue(member, "iPhone · Safari")).thenReturn(AuthTokenInfo.builder()
            .memberId(member.id()).role(SecurityRole.USER).accessToken("access").accessTokenExpiresIn(900).refreshToken("refresh")
            .pendingConsents(List.of(ConsentType.PRIVACY_POLICY)).reportWritable(false).build());

        AuthCookieResult<AuthTokenResponse> result = facade.generalLogin(AuthGeneralLoginCommand.builder()
            .email("  User@Example.COM ").password(PASSWORD).clientIp("203.0.113.10").deviceLabel("iPhone · Safari").build());

        assertThat(result.refreshToken()).isEqualTo("refresh");
        assertThat(result.response().memberId()).isEqualTo("1843956734582784");
        assertThat(result.response().role()).isEqualTo("USER");
        assertThat(result.response().accessToken()).isEqualTo("access");
        assertThat(result.response().accessTokenExpiresIn()).isEqualTo(900);
        assertThat(result.response().pendingConsents()).containsExactly("PRIVACY_POLICY");
        assertThat(result.response().reportWritable()).isFalse();
    }

    private static AuthGeneralSignupCommand command(String email, String nickname) {
        return AuthGeneralSignupCommand.builder()
            .email(email).password(PASSWORD).nickname(nickname)
            .termsAgreed(true).privacyAgreed(true).ageOver19Confirmed(true).sensitiveHealthInfoAgreed(false)
            .build();
    }
}
