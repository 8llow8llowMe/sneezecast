package com.sneezecast.domainlayer.auth.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.sneezecast.domainlayer.auth.application.command.AuthGeneralSignupCommand;
import com.sneezecast.domainlayer.auth.application.exception.AuthErrorCode;
import com.sneezecast.domainlayer.auth.application.exception.AuthException;
import com.sneezecast.domainlayer.auth.application.service.processor.EmailVerificationProcessor;
import com.sneezecast.domainlayer.auth.application.service.processor.GeneralSignupProcessor;
import com.sneezecast.domainlayer.member.application.exception.MemberErrorCode;
import com.sneezecast.domainlayer.member.application.exception.MemberException;
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
    private AuthWebFacade facade;

    @BeforeEach
    void setUp() {
        emailVerificationProcessor = mock(EmailVerificationProcessor.class);
        generalSignupProcessor = mock(GeneralSignupProcessor.class);
        facade = new AuthWebFacade(emailVerificationProcessor, generalSignupProcessor, passwordEncoder);
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
        facade.verifyEmailVerificationCode("USER@example.com", "ABCD2345", "203.0.113.10");

        verify(emailVerificationProcessor).sendCode(EMAIL, "203.0.113.10");
        verify(emailVerificationProcessor).verifyCode(EMAIL, "ABCD2345", "203.0.113.10");
    }

    private static AuthGeneralSignupCommand command(String email, String nickname) {
        return AuthGeneralSignupCommand.builder()
            .email(email).password(PASSWORD).nickname(nickname)
            .termsAgreed(true).privacyAgreed(true).ageOver19Confirmed(true).sensitiveHealthInfoAgreed(false)
            .build();
    }
}
