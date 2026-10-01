package com.sneezecast.domainlayer.auth.application.service.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sneezecast.domainlayer.auth.application.command.AuthGeneralSignupCommand;
import com.sneezecast.domainlayer.auth.application.exception.AuthErrorCode;
import com.sneezecast.domainlayer.auth.application.exception.AuthException;
import com.sneezecast.domainlayer.member.application.exception.MemberErrorCode;
import com.sneezecast.domainlayer.member.application.exception.MemberException;
import com.sneezecast.domainlayer.member.application.port.out.MemberRepositoryPort;
import com.sneezecast.domainlayer.member.application.service.processor.MemberConsentProcessor;
import com.sneezecast.domainlayer.member.domain.enums.MemberStatus;
import com.sneezecast.domainlayer.member.domain.model.Member;
import com.sneezecast.persistence.util.SnowflakeIdGenerator;
import com.sneezecast.security.common.enums.SecurityRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 가입 검증 규칙. 회원 · 동의의 원자성(실제 트랜잭션 프록시)은 {@code GeneralSignupTransactionTest} 가 본다.
 */
class GeneralSignupProcessorTest {

    private static final String EMAIL = "user@example.com";
    private static final String ENCODED_PASSWORD = "$2a$10$encodedPasswordHashForTest";

    private MemberRepositoryPort memberRepositoryPort;
    private MemberConsentProcessor memberConsentProcessor;
    private GeneralSignupProcessor processor;

    @BeforeEach
    void setUp() {
        memberRepositoryPort = mock(MemberRepositoryPort.class);
        memberConsentProcessor = mock(MemberConsentProcessor.class);
        when(memberRepositoryPort.save(any(Member.class))).thenAnswer(invocation -> invocation.getArgument(0));
        processor = new GeneralSignupProcessor(memberRepositoryPort, memberConsentProcessor, new SnowflakeIdGenerator(0, 0));
    }

    @Test
    @DisplayName("가입하면 USER · ACTIVE · 이메일 계정으로 저장하고, 비밀번호 칸에는 받은 해시만 남긴다")
    void signupSavesActiveUserWithGivenHash() {
        processor.signup(command(true, true, true, false), ENCODED_PASSWORD);

        ArgumentCaptor<Member> saved = ArgumentCaptor.forClass(Member.class);
        verify(memberRepositoryPort).save(saved.capture());
        Member member = saved.getValue();
        assertThat(member.id()).isPositive();
        assertThat(member.email()).isEqualTo(EMAIL);
        assertThat(member.nickname()).isEqualTo("재채기탐정");
        assertThat(member.role()).isEqualTo(SecurityRole.USER);
        assertThat(member.status()).isEqualTo(MemberStatus.ACTIVE);
        assertThat(member.provider()).isNull();
        assertThat(member.password()).isEqualTo(ENCODED_PASSWORD);
        verify(memberConsentProcessor).recordSignupConsents(member.id(), false);
    }

    @Test
    @DisplayName("건강정보 동의는 선택이다 — 동의하면 그 사실을 동의 기록에 넘긴다")
    void healthConsentIsOptionalAndForwarded() {
        processor.signup(command(true, true, true, true), ENCODED_PASSWORD);

        verify(memberConsentProcessor).recordSignupConsents(anyLong(), eq(true));
    }

    @Test
    @DisplayName("이용약관이나 개인정보 동의가 없으면 AUTH_008 이고 아무것도 저장하지 않는다")
    void missingRequiredConsentIsRejected() {
        assertThatThrownBy(() -> processor.signup(command(false, true, true, true), ENCODED_PASSWORD))
            .isInstanceOfSatisfying(AuthException.class, e -> assertThat(e.getErrorCode()).isEqualTo(AuthErrorCode.CONSENT_REQUIRED));
        assertThatThrownBy(() -> processor.signup(command(true, false, true, true), ENCODED_PASSWORD))
            .isInstanceOfSatisfying(AuthException.class, e -> assertThat(e.getErrorCode()).isEqualTo(AuthErrorCode.CONSENT_REQUIRED));

        verify(memberRepositoryPort, never()).save(any());
        verify(memberConsentProcessor, never()).recordSignupConsents(anyLong(), anyBoolean());
    }

    @Test
    @DisplayName("만 19세 이상 확인이 없으면 AUTH_009 다 — 동의 누락과 다른 코드다")
    void missingAgeConfirmationIsRejected() {
        assertThatThrownBy(() -> processor.signup(command(true, true, false, true), ENCODED_PASSWORD))
            .isInstanceOfSatisfying(AuthException.class, e -> assertThat(e.getErrorCode()).isEqualTo(AuthErrorCode.AGE_REQUIREMENT_NOT_MET));
        verify(memberRepositoryPort, never()).save(any());
    }

    @Test
    @DisplayName("이미 쓰이는 이메일이면 MEMBER_001 이고 저장하지 않는다 — 미끼 코드를 맞혀 인증 표시가 생긴 가입된 이메일도 여기서 막힌다")
    void existingEmailIsRejected() {
        when(memberRepositoryPort.existsByEmail(EMAIL)).thenReturn(true);

        assertThatThrownBy(() -> processor.signup(command(true, true, true, false), ENCODED_PASSWORD))
            .isInstanceOfSatisfying(MemberException.class, e -> assertThat(e.getErrorCode()).isEqualTo(MemberErrorCode.EXIST_MEMBER_EMAIL));
        verify(memberRepositoryPort, never()).save(any());
    }

    private static AuthGeneralSignupCommand command(boolean terms, boolean privacy, boolean age, boolean health) {
        return AuthGeneralSignupCommand.builder()
            .email(EMAIL).password("P@ssw0rd!").nickname("재채기탐정")
            .termsAgreed(terms).privacyAgreed(privacy).ageOver19Confirmed(age).sensitiveHealthInfoAgreed(health)
            .build();
    }
}
