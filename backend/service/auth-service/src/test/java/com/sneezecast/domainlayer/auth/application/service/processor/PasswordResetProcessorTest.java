package com.sneezecast.domainlayer.auth.application.service.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.sneezecast.domainlayer.auth.application.exception.AuthErrorCode;
import com.sneezecast.domainlayer.auth.application.exception.AuthException;
import com.sneezecast.domainlayer.auth.application.model.EmailCodePurpose;
import com.sneezecast.domainlayer.auth.application.port.out.LoginAttemptStorePort;
import com.sneezecast.domainlayer.auth.application.port.out.MailSendPort;
import com.sneezecast.domainlayer.auth.application.port.out.PasswordResetTokenStorePort;
import com.sneezecast.domainlayer.auth.application.service.support.PasswordResetTokenGenerator;
import com.sneezecast.domainlayer.auth.application.service.support.VerificationCodeGenerator;
import com.sneezecast.domainlayer.member.application.port.out.MemberRepositoryPort;
import com.sneezecast.domainlayer.member.domain.enums.MemberStatus;
import com.sneezecast.domainlayer.member.domain.enums.OAuthProvider;
import com.sneezecast.domainlayer.member.domain.model.Member;
import com.sneezecast.global.properties.EmailSendLimitProperties;
import com.sneezecast.global.properties.PasswordResetProperties;
import com.sneezecast.security.common.enums.SecurityRole;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PasswordResetProcessorTest {

    private static final String EMAIL = "user@example.com";
    private static final String SOCIAL_EMAIL = "kakao@example.com";
    private static final String LINKED_EMAIL = "linked@example.com";
    private static final String UNKNOWN_EMAIL = "nobody@example.com";
    private static final String WITHDRAWN_EMAIL = "withdrawn@example.com";
    private static final String SUSPENDED_EMAIL = "suspended@example.com";
    private static final String CLIENT_IP = "203.0.113.10";
    private static final EmailSendLimitProperties LIMITS = new EmailSendLimitProperties(
        10, Duration.ofHours(1), Duration.ofSeconds(60), Duration.ofMinutes(5), Duration.ofMinutes(30), 5, 20, Duration.ofHours(1));
    private static final PasswordResetProperties RESET = new PasswordResetProperties(Duration.ofMinutes(15));

    private FakeEmailVerificationStore codeStore;
    private FakePasswordResetTokenStore tokenStore;
    private MailSendPort mailSendPort;
    private MemberRepositoryPort memberRepositoryPort;
    private LoginAttemptStorePort loginAttemptStorePort;
    private PasswordResetProcessor processor;

    @BeforeEach
    void setUp() {
        codeStore = new FakeEmailVerificationStore();
        tokenStore = new FakePasswordResetTokenStore();
        mailSendPort = mock(MailSendPort.class);
        memberRepositoryPort = mock(MemberRepositoryPort.class);
        loginAttemptStorePort = mock(LoginAttemptStorePort.class);
        when(memberRepositoryPort.findByEmail(EMAIL)).thenReturn(Optional.of(member(1L, EMAIL, MemberStatus.ACTIVE, null)));
        when(memberRepositoryPort.findByEmail(SOCIAL_EMAIL)).thenReturn(Optional.of(member(2L, SOCIAL_EMAIL, MemberStatus.ACTIVE, OAuthProvider.KAKAO)));
        when(memberRepositoryPort.findByEmail(LINKED_EMAIL)).thenReturn(Optional.of(Member.builder().id(5L).email(LINKED_EMAIL).password("$2a$10$hash")
            .nickname("닉네임").role(SecurityRole.USER).provider(OAuthProvider.KAKAO).status(MemberStatus.ACTIVE).build()));
        when(memberRepositoryPort.findByEmail(UNKNOWN_EMAIL)).thenReturn(Optional.empty());
        when(memberRepositoryPort.findByEmail(WITHDRAWN_EMAIL)).thenReturn(Optional.of(member(3L, WITHDRAWN_EMAIL, MemberStatus.WITHDRAWN, null)));
        when(memberRepositoryPort.findByEmail(SUSPENDED_EMAIL)).thenReturn(Optional.of(member(4L, SUSPENDED_EMAIL, MemberStatus.SUSPENDED, null)));
        processor = new PasswordResetProcessor(new EmailCodeProcessor(codeStore, LIMITS, new VerificationCodeGenerator()), tokenStore, mailSendPort,
            memberRepositoryPort, loginAttemptStorePort, new PasswordResetTokenGenerator(), RESET, LIMITS);
    }

    @Test
    @DisplayName("비밀번호가 있는 정상 회원이면 6자리 코드를 저장하고 같은 코드를 재설정 메일로 보낸다 — 카카오가 연결된 이메일 계정도 받는다. 키는 재설정 목적만 쓴다")
    void activeMemberGetsResetCode() {
        processor.sendCode(EMAIL, CLIENT_IP);
        processor.sendCode(LINKED_EMAIL, CLIENT_IP);

        ArgumentCaptor<String> mailed = ArgumentCaptor.forClass(String.class);
        verify(mailSendPort).sendPasswordResetCode(eq(EMAIL), mailed.capture());
        verify(mailSendPort).sendPasswordResetCode(eq(LINKED_EMAIL), anyString());
        assertThat(mailed.getValue()).matches("[0-9]{6}");
        assertThat(codeStore.codes).containsEntry(EMAIL, mailed.getValue());
        assertThat(codeStore.codeTtls).containsEntry(EMAIL, LIMITS.codeTtl());
        assertThat(codeStore.purposes).containsExactly(EmailCodePurpose.PASSWORD_RESET);
    }

    @Test
    @DisplayName("카카오로만 로그인하는(비밀번호 없는) 회원에게는 코드를 보내지 않는다 — 미끼 코드만 저장하고 '카카오로 로그인해 주세요' 안내만 보낸다")
    void kakaoOnlyMemberGetsDecoyAndKakaoNotice() {
        processor.sendCode(SOCIAL_EMAIL, CLIENT_IP);

        verify(mailSendPort).sendPasswordResetOAuthOnlyNotice(SOCIAL_EMAIL);
        verify(mailSendPort, never()).sendPasswordResetCode(anyString(), anyString());
        assertThat(codeStore.codes.get(SOCIAL_EMAIL)).matches("[0-9]{6}");
        assertThat(codeStore.codeTtls).containsEntry(SOCIAL_EMAIL, LIMITS.codeTtl());
    }

    @Test
    @DisplayName("카카오로만 로그인하는 회원도 발송 → 오답 검증 응답이 정상 회원과 같다 — 계정 종류가 응답으로 새지 않는다")
    void kakaoOnlyMemberResponsesAreUniform() {
        assertThat(sendThenWrongVerifications(SOCIAL_EMAIL)).isEqualTo(sendThenWrongVerifications(EMAIL));
    }

    @Test
    @DisplayName("회원이 없으면 같은 수명의 미끼 코드를 저장하고, 코드 대신 '가입된 계정이 없다' 안내만 보낸다")
    void unknownEmailGetsDecoyAndNoAccountNotice() {
        processor.sendCode(UNKNOWN_EMAIL, CLIENT_IP);

        verify(mailSendPort).sendPasswordResetNoAccountNotice(UNKNOWN_EMAIL);
        verify(mailSendPort, never()).sendPasswordResetCode(anyString(), anyString());
        assertThat(codeStore.codes.get(UNKNOWN_EMAIL)).matches("[0-9]{6}");
        assertThat(codeStore.codeTtls).containsEntry(UNKNOWN_EMAIL, LIMITS.codeTtl());
    }

    @Test
    @DisplayName("탈퇴 · 정지 회원은 미끼 코드만 저장하고 메일을 보내지 않는다")
    void inactiveMemberGetsDecoyOnly() {
        processor.sendCode(WITHDRAWN_EMAIL, CLIENT_IP);
        processor.sendCode(SUSPENDED_EMAIL, CLIENT_IP);

        verifyNoInteractions(mailSendPort);
        assertThat(codeStore.codes).containsKeys(WITHDRAWN_EMAIL, SUSPENDED_EMAIL);
    }

    @Test
    @DisplayName("발송 → 오답 검증의 응답이 정상 · 미가입 · 탈퇴에서 끝까지 같고, 가입 인증과 같은 코드다(AUTH_003 … AUTH_005 → AUTH_004)")
    void verifyResponsesAreUniformAndMatchSignupCodes() {
        List<String> active = sendThenWrongVerifications(EMAIL);
        assertThat(sendThenWrongVerifications(UNKNOWN_EMAIL)).isEqualTo(active);
        assertThat(sendThenWrongVerifications(WITHDRAWN_EMAIL)).isEqualTo(active);
        assertThat(active).containsExactly(
            "AUTH_003:인증코드가 일치하지 않습니다.", "AUTH_003:인증코드가 일치하지 않습니다.", "AUTH_003:인증코드가 일치하지 않습니다.",
            "AUTH_003:인증코드가 일치하지 않습니다.", "AUTH_005:인증코드 시도 횟수를 초과했습니다. 인증코드를 다시 요청해주세요.",
            "AUTH_004:인증코드가 만료되었거나 요청 이력이 없습니다. 다시 요청해주세요.");
    }

    @Test
    @DisplayName("쿨다운 · IP 발송 상한도 가입과 같은 코드다 (AUTH_001 · AUTH_002)")
    void sendLimitsMatchSignupCodes() {
        processor.sendCode(EMAIL, CLIENT_IP);
        assertThat(catchThrowableOfType(AuthException.class, () -> processor.sendCode(EMAIL, CLIENT_IP)).getErrorCode())
            .isEqualTo(AuthErrorCode.EMAIL_CODE_COOLDOWN);

        codeStore.ipSendCounts.put(CLIENT_IP, (long) LIMITS.ipMaxSendCount());
        assertThat(catchThrowableOfType(AuthException.class, () -> processor.sendCode(UNKNOWN_EMAIL, CLIENT_IP)).getErrorCode())
            .isEqualTo(AuthErrorCode.EMAIL_SEND_IP_LIMITED);
    }

    @Test
    @DisplayName("코드가 맞으면 토큰을 주고, 저장소에는 토큰 원문이 아니라 SHA-256 해시를 키로 이메일을 토큰 수명만큼 둔다 — 코드 · 실패 카운터는 지운다")
    void verifyCodeIssuesHashedToken() {
        codeStore.codes.put(EMAIL, "482913");
        codeStore.failures.put(EMAIL, 2L);

        String token = processor.verifyCode(EMAIL, " 482913 ", CLIENT_IP);

        assertThat(token).matches("[A-Za-z0-9_-]{43}");
        assertThat(tokenStore.tokens).containsOnlyKeys(PasswordResetTokenGenerator.hash(token)).doesNotContainKey(token)
            .containsEntry(PasswordResetTokenGenerator.hash(token), EMAIL);
        assertThat(tokenStore.ttls).containsEntry(PasswordResetTokenGenerator.hash(token), RESET.tokenTtl());
        assertThat(codeStore.codes).doesNotContainKey(EMAIL);
        assertThat(codeStore.failures).doesNotContainKey(EMAIL);
    }

    @Test
    @DisplayName("미끼 코드를 맞혀도 같은 모양의 토큰을 준다 — 응답이 가입 여부를 뜻하지 않는다")
    void decoyCodeAlsoGetsToken() {
        processor.sendCode(UNKNOWN_EMAIL, CLIENT_IP);

        assertThat(processor.verifyCode(UNKNOWN_EMAIL, codeStore.codes.get(UNKNOWN_EMAIL), CLIENT_IP)).matches("[A-Za-z0-9_-]{43}");
    }

    @Test
    @DisplayName("토큰은 1회용이다 — 한 번 소비하면 같은 토큰은 AUTH_018 이다")
    void tokenIsSingleUse() {
        codeStore.codes.put(EMAIL, "482913");
        String token = processor.verifyCode(EMAIL, "482913", CLIENT_IP);

        assertThat(processor.consumeToken(token, CLIENT_IP).id()).isEqualTo(1L);
        assertThat(resetFailure(token)).isEqualTo(AuthErrorCode.PASSWORD_RESET_EXPIRED);
        assertThat(tokenStore.tokens).isEmpty();
    }

    @Test
    @DisplayName("없는 토큰 · 미가입 · 탈퇴 · 정지 · 비밀번호 없는(카카오만) 회원의 토큰은 모두 같은 AUTH_018 이다 — 계정 상태가 새지 않는다")
    void invalidAndInactiveTokensFailAlike() {
        assertThat(resetFailure("never-issued-token")).isEqualTo(AuthErrorCode.PASSWORD_RESET_EXPIRED);
        // 카카오로만 로그인하는 회원은 비밀번호가 없어 재설정할 수 없다 — 코드를 보내지 않으니 미끼 코드를 맞힌 경우다.
        for (String email : List.of(UNKNOWN_EMAIL, WITHDRAWN_EMAIL, SUSPENDED_EMAIL, SOCIAL_EMAIL)) {
            codeStore.codes.put(email, "482913");
            String token = processor.verifyCode(email, "482913", CLIENT_IP);
            assertThat(resetFailure(token)).as(email).isEqualTo(AuthErrorCode.PASSWORD_RESET_EXPIRED);
        }
    }

    @Test
    @DisplayName("IP 시도 상한을 넘으면 토큰을 보기 전에 AUTH_019 다 — 토큰은 소비되지 않고 남는다")
    void ipLimitStopsBeforeTokenLookup() {
        codeStore.codes.put(EMAIL, "482913");
        String token = processor.verifyCode(EMAIL, "482913", CLIENT_IP);
        tokenStore.ipCounts.put(CLIENT_IP, (long) LIMITS.verifyIpMaxCount());

        assertThat(resetFailure(token)).isEqualTo(AuthErrorCode.PASSWORD_RESET_IP_LIMITED);
        assertThat(tokenStore.consumeCalls).isZero();
        assertThat(tokenStore.tokens).containsKey(PasswordResetTokenGenerator.hash(token));
    }

    @Test
    @DisplayName("IP 카운터가 장애로 0 이면(fail-open) 막지 않는다")
    void ipCounterFailOpenDoesNotBlock() {
        codeStore.codes.put(EMAIL, "482913");
        String token = processor.verifyCode(EMAIL, "482913", CLIENT_IP);
        tokenStore.failOpen = true;

        assertThat(processor.consumeToken(token, CLIENT_IP).email()).isEqualTo(EMAIL);
    }

    @Test
    @DisplayName("로그인 잠금 해제는 그 이메일의 실패 카운터 · 잠금을 지운다")
    void releaseLoginLockClearsFailures() {
        processor.releaseLoginLock(EMAIL);

        verify(loginAttemptStorePort).clearFailures(EMAIL);
    }

    private AuthErrorCode resetFailure(String token) {
        return catchThrowableOfType(AuthException.class, () -> processor.consumeToken(token, CLIENT_IP)).getErrorCode();
    }

    /** 발송 후 오답을 최대 실패 횟수 + 1 번 넣어 응답을 "코드:메시지" 로 모은다. */
    private List<String> sendThenWrongVerifications(String email) {
        processor.sendCode(email, CLIENT_IP);
        codeStore.ipVerifyCounts.clear();
        List<String> responses = new ArrayList<>();
        for (int i = 0; i <= LIMITS.maxVerifyFailures(); i++) {
            AuthException exception = catchThrowableOfType(AuthException.class, () -> processor.verifyCode(email, "WRONG234", CLIENT_IP));
            responses.add(exception.getErrorCode().getCode() + ":" + exception.getMessage());
            codeStore.ipVerifyCounts.clear();
        }
        return responses;
    }

    private static Member member(long id, String email, MemberStatus status, OAuthProvider provider) {
        return Member.builder().id(id).email(email).password(provider == null ? "$2a$10$hash" : null).nickname("닉네임").role(SecurityRole.USER)
            .provider(provider).status(status).build();
    }

    /** TTL 은 흉내 내지 않는다. 소비는 꺼내고 지운다(원자성은 통합 테스트가 실제 Redis 로 본다). */
    private static class FakePasswordResetTokenStore implements PasswordResetTokenStorePort {

        private final Map<String, String> tokens = new HashMap<>();
        private final Map<String, Duration> ttls = new HashMap<>();
        private final Map<String, Long> ipCounts = new HashMap<>();
        private int consumeCalls;
        private boolean failOpen;

        @Override
        public void saveToken(String tokenHash, String email, Duration ttl) {
            tokens.put(tokenHash, email);
            ttls.put(tokenHash, ttl);
        }

        @Override
        public Optional<String> consumeToken(String tokenHash) {
            consumeCalls++;
            return Optional.ofNullable(tokens.remove(tokenHash));
        }

        @Override
        public long increaseIpResetCount(String clientIp, Duration window) {
            return failOpen ? 0L : ipCounts.merge(clientIp, 1L, Long::sum);
        }
    }
}
