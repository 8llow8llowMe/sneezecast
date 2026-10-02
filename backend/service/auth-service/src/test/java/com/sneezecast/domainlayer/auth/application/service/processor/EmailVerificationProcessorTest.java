package com.sneezecast.domainlayer.auth.application.service.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sneezecast.domainlayer.auth.application.exception.AuthErrorCode;
import com.sneezecast.domainlayer.auth.application.exception.AuthException;
import com.sneezecast.domainlayer.auth.application.model.EmailCodePurpose;
import com.sneezecast.domainlayer.auth.application.port.out.EmailVerificationStorePort;
import com.sneezecast.domainlayer.auth.application.port.out.MailSendPort;
import com.sneezecast.domainlayer.auth.application.service.support.VerificationCodeGenerator;
import com.sneezecast.domainlayer.member.application.port.out.MemberRepositoryPort;
import com.sneezecast.global.properties.EmailSendLimitProperties;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class EmailVerificationProcessorTest {

    private static final String EMAIL = "user@example.com";
    private static final String REGISTERED_EMAIL = "registered@example.com";
    private static final String CLIENT_IP = "203.0.113.10";
    private static final EmailSendLimitProperties LIMITS = new EmailSendLimitProperties(
        3, Duration.ofHours(1), Duration.ofSeconds(60), Duration.ofMinutes(5), Duration.ofMinutes(30), 5, 20, Duration.ofHours(1));

    private FakeEmailVerificationStore store;
    private MailSendPort mailSendPort;
    private MemberRepositoryPort memberRepositoryPort;
    private EmailVerificationProcessor processor;

    @BeforeEach
    void setUp() {
        store = new FakeEmailVerificationStore();
        mailSendPort = mock(MailSendPort.class);
        memberRepositoryPort = mock(MemberRepositoryPort.class);
        when(memberRepositoryPort.existsByEmail(REGISTERED_EMAIL)).thenReturn(true);
        processor = new EmailVerificationProcessor(new EmailCodeProcessor(store, LIMITS, new VerificationCodeGenerator()), store, mailSendPort, memberRepositoryPort,
            LIMITS);
    }

    @Test
    @DisplayName("미가입 이메일이면 6자리 숫자 코드를 코드 수명으로 저장하고 같은 코드를 메일로 보낸다")
    void sendCodeStoresAndMailsCode() {
        processor.sendCode(EMAIL, CLIENT_IP);

        ArgumentCaptor<String> mailed = ArgumentCaptor.forClass(String.class);
        verify(mailSendPort).sendVerificationCode(eq(EMAIL), mailed.capture());
        assertThat(store.codes).containsEntry(EMAIL, mailed.getValue());
        assertThat(mailed.getValue()).matches("[0-9]{6}");
        assertThat(store.codeTtls).containsEntry(EMAIL, LIMITS.codeTtl());
        assertThat(store.purposes).as("가입 키만 쓴다 — 재설정 코드 · 카운터를 건드리지 않는다").containsExactly(EmailCodePurpose.SIGNUP);
    }

    @Test
    @DisplayName("가입된 이메일에도 같은 수명으로 미끼 코드를 저장하되, 메일로는 코드가 아니라 안내만 보낸다")
    void registeredEmailGetsDecoyCodeAndNoticeOnly() {
        store.failures.put(REGISTERED_EMAIL, 3L);

        processor.sendCode(REGISTERED_EMAIL, CLIENT_IP);

        verify(mailSendPort).sendAlreadyRegisteredNotice(REGISTERED_EMAIL);
        verify(mailSendPort, never()).sendVerificationCode(anyString(), anyString());
        assertThat(store.codes.get(REGISTERED_EMAIL)).matches("[0-9]{6}");
        assertThat(store.codeTtls).containsEntry(REGISTERED_EMAIL, LIMITS.codeTtl());
        assertThat(store.failures).doesNotContainKey(REGISTERED_EMAIL);
    }

    @Test
    @DisplayName("가입 · 미가입 이메일에서 발송 → 오답 검증의 응답 코드 · 메시지가 끝까지(AUTH_003 … AUTH_005 → AUTH_004) 같다 — 가입 여부가 새지 않는다")
    void verifyResponsesDoNotRevealRegistration() {
        assertThat(sendThenWrongVerifications(REGISTERED_EMAIL)).isEqualTo(sendThenWrongVerifications(EMAIL))
            .containsExactly(
                "AUTH_003:인증코드가 일치하지 않습니다.", "AUTH_003:인증코드가 일치하지 않습니다.", "AUTH_003:인증코드가 일치하지 않습니다.",
                "AUTH_003:인증코드가 일치하지 않습니다.", "AUTH_005:인증코드 시도 횟수를 초과했습니다. 인증코드를 다시 요청해주세요.",
                "AUTH_004:인증코드가 만료되었거나 요청 이력이 없습니다. 다시 요청해주세요.");
    }

    @Test
    @DisplayName("쿨다운 안에 다시 요청하면 AUTH_001 이고, 막힌 요청은 IP 발송 상한에 세지 않는다")
    void resendWithinCooldownIsRejectedWithoutCounting() {
        processor.sendCode(EMAIL, CLIENT_IP);

        assertThatThrownBy(() -> processor.sendCode(EMAIL, CLIENT_IP))
            .isInstanceOfSatisfying(AuthException.class, e -> assertThat(e.getErrorCode()).isEqualTo(AuthErrorCode.EMAIL_CODE_COOLDOWN));
        verify(mailSendPort).sendVerificationCode(eq(EMAIL), anyString());
        assertThat(store.ipSendCounts).containsEntry(CLIENT_IP, 1L);
    }

    @Test
    @DisplayName("IP 발송 상한에 닿으면 AUTH_002 이고, 쿨다운을 잡지도 세지도 않는다")
    void ipLimitIsCheckedBeforeCooldown() {
        for (int i = 0; i < LIMITS.ipMaxSendCount(); i++) {
            processor.sendCode("user" + i + "@example.com", CLIENT_IP);
        }

        assertThatThrownBy(() -> processor.sendCode("next@example.com", CLIENT_IP))
            .isInstanceOfSatisfying(AuthException.class, e -> assertThat(e.getErrorCode()).isEqualTo(AuthErrorCode.EMAIL_SEND_IP_LIMITED));
        assertThat(store.cooldowns).doesNotContain("next@example.com");
        assertThat(store.ipSendCounts).containsEntry(CLIENT_IP, (long) LIMITS.ipMaxSendCount());
    }

    @Test
    @DisplayName("코드가 맞으면 인증 완료 표시를 남기고 코드와 실패 횟수를 지운다 — 앞뒤 공백이 붙은 입력도 맞는 코드다")
    void verifyCodeMarksVerified() {
        store.codes.put(EMAIL, "482913");
        store.failures.put(EMAIL, 2L);

        processor.verifyCode(EMAIL, " 482913 ", CLIENT_IP);

        assertThat(store.verified).containsEntry(EMAIL, LIMITS.verifiedTtl());
        assertThat(store.codes).doesNotContainKey(EMAIL);
        assertThat(store.failures).doesNotContainKey(EMAIL);
    }

    @Test
    @DisplayName("코드가 없으면(만료 · 요청 이력 없음) AUTH_004 다")
    void expiredCodeIsRejected() {
        assertThatThrownBy(() -> processor.verifyCode(EMAIL, "482913", CLIENT_IP))
            .isInstanceOfSatisfying(AuthException.class, e -> assertThat(e.getErrorCode()).isEqualTo(AuthErrorCode.EXPIRED_EMAIL_CODE));
        assertThat(store.verified).isEmpty();
    }

    @Test
    @DisplayName("IP 검증 상한을 넘으면 코드를 보기 전에 AUTH_010 이다 — 여러 이메일에 걸친 대입을 늦춘다")
    void verifyIpLimitStopsBeforeCodeCheck() {
        store.codes.put(EMAIL, "482913");
        store.ipVerifyCounts.put(CLIENT_IP, (long) LIMITS.verifyIpMaxCount());

        assertThatThrownBy(() -> processor.verifyCode(EMAIL, "482913", CLIENT_IP))
            .isInstanceOfSatisfying(AuthException.class, e -> assertThat(e.getErrorCode()).isEqualTo(AuthErrorCode.EMAIL_VERIFY_IP_LIMITED));
        assertThat(store.verified).isEmpty();
        assertThat(store.failures).isEmpty();
    }

    @Test
    @DisplayName("새 코드를 받으면 이전 실패 횟수가 지워진다")
    void newCodeClearsPreviousFailures() {
        store.failures.put(EMAIL, 4L);

        processor.sendCode(EMAIL, CLIENT_IP);

        assertThat(store.failures).doesNotContainKey(EMAIL);
    }

    @Test
    @DisplayName("인증 완료 표시가 없으면 가입 전 검사가 AUTH_007 이다")
    void requireVerifiedRejectsUnverifiedEmail() {
        assertThatThrownBy(() -> processor.requireVerified(EMAIL))
            .isInstanceOfSatisfying(AuthException.class, e -> assertThat(e.getErrorCode()).isEqualTo(AuthErrorCode.EMAIL_NOT_VERIFIED));

        store.verified.put(EMAIL, LIMITS.verifiedTtl());
        assertThatCode(() -> processor.requireVerified(EMAIL)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("가입 뒤 표시 소비가 저장소 장애로 실패해도 예외를 올리지 않는다 — 가입은 이미 커밋됐다")
    void consumeVerifiedSwallowsStoreFailure() {
        EmailVerificationStorePort failingStore = mock(EmailVerificationStorePort.class);
        doThrow(new AuthException(AuthErrorCode.EMAIL_VERIFICATION_UNAVAILABLE)).when(failingStore).deleteVerified(EMAIL);
        EmailVerificationProcessor failing = new EmailVerificationProcessor(new EmailCodeProcessor(failingStore, LIMITS, new VerificationCodeGenerator()),
            failingStore, mailSendPort, memberRepositoryPort, LIMITS);

        assertThatCode(() -> failing.consumeVerified(EMAIL)).doesNotThrowAnyException();
    }

    /** 발송 후 오답을 최대 실패 횟수 + 1 번 넣어 응답을 "코드:메시지" 로 모은다. */
    private List<String> sendThenWrongVerifications(String email) {
        processor.sendCode(email, CLIENT_IP);
        List<String> responses = new ArrayList<>();
        for (int i = 0; i <= LIMITS.maxVerifyFailures(); i++) {
            AuthException exception = catchThrowableOfType(AuthException.class, () -> processor.verifyCode(email, "WRONG234", CLIENT_IP));
            responses.add(exception.getErrorCode().getCode() + ":" + exception.getMessage());
        }
        return responses;
    }
}
