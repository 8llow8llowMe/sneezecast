package com.sneezecast.domainlayer.auth.application.service.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.sneezecast.domainlayer.auth.application.exception.AuthErrorCode;
import com.sneezecast.domainlayer.auth.application.exception.AuthException;
import com.sneezecast.domainlayer.auth.application.port.out.LoginAttemptStorePort;
import com.sneezecast.domainlayer.member.application.exception.MemberException;
import com.sneezecast.domainlayer.member.application.port.out.MemberRepositoryPort;
import com.sneezecast.domainlayer.member.domain.enums.MemberStatus;
import com.sneezecast.domainlayer.member.domain.model.Member;
import com.sneezecast.global.properties.LoginAttemptProperties;
import com.sneezecast.security.common.enums.SecurityRole;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.security.crypto.password.PasswordEncoder;

class GeneralLoginProcessorTest {

    private static final String EMAIL = "user@example.com";
    private static final String UNKNOWN_EMAIL = "nobody@example.com";
    private static final String PASSWORD = "P@ssw0rd!";
    private static final String HASH = "$2a$10$member-hash";
    private static final String DUMMY_HASH = "$2a$10$dummy-hash";
    private static final String CLIENT_IP = "203.0.113.10";
    private static final LoginAttemptProperties LIMITS = new LoginAttemptProperties(3, Duration.ofMinutes(10), 5, Duration.ofHours(1));

    private FakeLoginAttemptStore store;
    private MemberRepositoryPort memberRepositoryPort;
    private PasswordEncoder passwordEncoder;
    private GeneralLoginProcessor processor;

    @BeforeEach
    void setUp() {
        store = new FakeLoginAttemptStore();
        memberRepositoryPort = mock(MemberRepositoryPort.class);
        passwordEncoder = mock(PasswordEncoder.class);
        when(passwordEncoder.encode(anyString())).thenReturn(DUMMY_HASH);
        when(passwordEncoder.matches(PASSWORD, HASH)).thenReturn(true);
        when(memberRepositoryPort.findByEmail(EMAIL)).thenReturn(Optional.of(member(MemberStatus.ACTIVE, HASH)));
        when(memberRepositoryPort.findByEmail(UNKNOWN_EMAIL)).thenReturn(Optional.empty());
        processor = new GeneralLoginProcessor(memberRepositoryPort, passwordEncoder, store, LIMITS);
    }

    @Test
    @DisplayName("비밀번호가 맞으면 회원을 돌려주고 이메일 카운터 · 잠금을 지운다 — IP 카운터는 먼저 올린 자기 몫만 되돌린다(공유 IP 의 다른 실패는 남는다)")
    void successClearsEmailAndRevertsOwnIpAttempt() {
        store.emailFailures.put(EMAIL, 2L);
        store.ipAttempts.put(CLIENT_IP, 2L);

        Member member = processor.authenticate(EMAIL, PASSWORD, CLIENT_IP);

        assertThat(member.id()).isEqualTo(42L);
        assertThat(store.emailFailures).doesNotContainKey(EMAIL);
        assertThat(store.cleared).containsExactly(EMAIL);
        assertThat(store.ipAttempts).containsEntry(CLIENT_IP, 2L);
    }

    @Test
    @DisplayName("미가입 이메일도 LOGIN_FAILED 이고 이메일 · IP 시도로 남는다 — 더미 해시로 BCrypt 비교를 한 번 돌려 응답 시간을 맞춘다")
    void unknownEmailIsCountedAndTimed() {
        assertThatThrownBy(() -> processor.authenticate(UNKNOWN_EMAIL, PASSWORD, CLIENT_IP))
            .isInstanceOfSatisfying(AuthException.class, e -> assertThat(e.getErrorCode()).isEqualTo(AuthErrorCode.LOGIN_FAILED));

        verify(passwordEncoder).matches(PASSWORD, DUMMY_HASH);
        assertThat(store.emailFailures).containsEntry(UNKNOWN_EMAIL, 1L);
        assertThat(store.ipAttempts).containsEntry(CLIENT_IP, 1L);
    }

    @Test
    @DisplayName("비밀번호가 없는(소셜) 계정도 같은 LOGIN_FAILED 이고 더미 해시로 비교한다")
    void accountWithoutPasswordFailsLikeUnknown() {
        when(memberRepositoryPort.findByEmail(EMAIL)).thenReturn(Optional.of(member(MemberStatus.ACTIVE, null)));

        assertThatThrownBy(() -> processor.authenticate(EMAIL, PASSWORD, CLIENT_IP))
            .isInstanceOfSatisfying(AuthException.class, e -> assertThat(e.getErrorCode()).isEqualTo(AuthErrorCode.LOGIN_FAILED));
        verify(passwordEncoder).matches(PASSWORD, DUMMY_HASH);
    }

    @Test
    @DisplayName("정해진 횟수째 실패에서 잠그고 LOGIN_ATTEMPT_LOCKED 이다 — 잠긴 동안은 회원 조회 · BCrypt 없이 같은 코드다")
    void locksOnThresholdAndSkipsLookupWhileLocked() {
        assertThat(failureCode(EMAIL, "wrong")).isEqualTo(AuthErrorCode.LOGIN_FAILED);
        assertThat(failureCode(EMAIL, "wrong")).isEqualTo(AuthErrorCode.LOGIN_FAILED);
        assertThat(failureCode(EMAIL, "wrong")).isEqualTo(AuthErrorCode.LOGIN_ATTEMPT_LOCKED);
        assertThat(store.locked).containsExactly(EMAIL);
        assertThat(store.emailFailures).as("잠가도 카운터는 남긴다 — 잠금 직전에 통과한 동시 요청이 0 부터 다시 세지 않게").containsEntry(EMAIL, 3L);

        clearInvocations(memberRepositoryPort, passwordEncoder);
        assertThat(failureCode(EMAIL, PASSWORD)).as("맞는 비밀번호도 잠금 중이면 막는다").isEqualTo(AuthErrorCode.LOGIN_ATTEMPT_LOCKED);
        verifyNoInteractions(memberRepositoryPort);
        verify(passwordEncoder, never()).matches(anyString(), anyString());
    }

    @Test
    @DisplayName("이메일 카운터가 이미 상한이면(잠금 직전에 몰린 요청) 비밀번호를 비교하지 않고 잠근다 — BCrypt 가 한 번도 돌지 않는다")
    void attemptOverEmailLimitSkipsBcrypt() {
        store.emailFailures.put(EMAIL, (long) LIMITS.maxFailureCount());

        assertThat(failureCode(EMAIL, PASSWORD)).isEqualTo(AuthErrorCode.LOGIN_ATTEMPT_LOCKED);

        verify(passwordEncoder, never()).matches(anyString(), anyString());
        verify(memberRepositoryPort, never()).findByEmail(anyString());
        assertThat(store.locked).containsExactly(EMAIL);
    }

    @Test
    @DisplayName("같은 이메일로 틀린 비밀번호가 동시에 몰려도 BCrypt 비교는 이메일 상한 이하로만 돈다 — 카운터를 비교 전에 원자적으로 올린다")
    void concurrentAttemptsAreBoundedByEmailLimit() throws Exception {
        CountingPasswordEncoder countingEncoder = new CountingPasswordEncoder();
        GeneralLoginProcessor concurrentProcessor = new GeneralLoginProcessor(memberRepositoryPort, countingEncoder, store,
            new LoginAttemptProperties(3, Duration.ofMinutes(10), 10_000, Duration.ofHours(1)));
        int attempts = 64;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(16);
        try {
            List<Future<AuthErrorCode>> results = new ArrayList<>();
            for (int i = 0; i < attempts; i++) {
                results.add(executor.submit(() -> {
                    start.await();
                    return catchThrowableOfType(AuthException.class, () -> concurrentProcessor.authenticate(EMAIL, "wrong", CLIENT_IP)).getErrorCode();
                }));
            }
            start.countDown();
            List<AuthErrorCode> codes = new ArrayList<>();
            for (Future<AuthErrorCode> result : results) {
                codes.add(result.get(10, TimeUnit.SECONDS));
            }

            assertThat(countingEncoder.matchesCalls.get()).isLessThanOrEqualTo(3);
            assertThat(codes).filteredOn(code -> code == AuthErrorCode.LOGIN_FAILED).hasSizeLessThanOrEqualTo(2);
            assertThat(codes).containsOnly(AuthErrorCode.LOGIN_FAILED, AuthErrorCode.LOGIN_ATTEMPT_LOCKED);
        } finally {
            executor.shutdownNow();
        }
    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({"WITHDRAWN, MEMBER_002", "SUSPENDED, MEMBER_003"})
    @DisplayName("탈퇴 · 정지는 비밀번호가 맞을 때만 상태 코드로 알린다 — 맞은 비밀번호는 추측 실패가 아니라 카운터를 되돌린다")
    void inactiveStatusOnlyWithCorrectPassword(MemberStatus status, String expectedCode) {
        when(memberRepositoryPort.findByEmail(EMAIL)).thenReturn(Optional.of(member(status, HASH)));

        assertThatThrownBy(() -> processor.authenticate(EMAIL, PASSWORD, CLIENT_IP))
            .isInstanceOfSatisfying(MemberException.class, e -> assertThat(e.getErrorCode().getCode()).isEqualTo(expectedCode));
        assertThat(store.cleared).containsExactly(EMAIL);
        assertThat(store.ipAttempts).containsEntry(CLIENT_IP, 0L);
        assertThat(failureCode(EMAIL, "wrong")).as("틀린 비밀번호로는 상태를 떠볼 수 없다").isEqualTo(AuthErrorCode.LOGIN_FAILED);
    }

    @Test
    @DisplayName("IP 시도가 상한을 넘으면 회원 조회 · BCrypt 없이 LOGIN_IP_LIMITED 다 — 이메일을 바꿔도 막힌다")
    void ipLimitBlocksBeforeLookup() {
        store.ipAttempts.put(CLIENT_IP, (long) LIMITS.ipMaxFailureCount());

        assertThat(failureCode("other@example.com", PASSWORD)).isEqualTo(AuthErrorCode.LOGIN_IP_LIMITED);
        verifyNoInteractions(memberRepositoryPort);
        verify(passwordEncoder, never()).matches(anyString(), anyString());
        assertThat(processor.authenticate(EMAIL, PASSWORD, "198.51.100.7").id()).as("다른 IP 는 영향이 없다").isEqualTo(42L);
    }

    @Test
    @DisplayName("IP 상한 직전까지의 실패는 통과시키고, 상한 다음 시도부터 막는다")
    void ipLimitAllowsUpToLimit() {
        store.ipAttempts.put(CLIENT_IP, (long) LIMITS.ipMaxFailureCount() - 1);

        assertThat(processor.authenticate(EMAIL, PASSWORD, CLIENT_IP).id()).isEqualTo(42L);
        assertThat(store.ipAttempts).as("성공은 자기 몫을 되돌린다").containsEntry(CLIENT_IP, (long) LIMITS.ipMaxFailureCount() - 1);
        assertThat(failureCode(UNKNOWN_EMAIL, PASSWORD)).isEqualTo(AuthErrorCode.LOGIN_FAILED);
        assertThat(failureCode(UNKNOWN_EMAIL, PASSWORD)).isEqualTo(AuthErrorCode.LOGIN_IP_LIMITED);
    }

    @Test
    @DisplayName("저장소 장애(fail-open: 카운터 0 · 잠금 없음)여도 로그인은 진행되고, 실패는 잠그지 않고 LOGIN_FAILED 만 낸다")
    void storeOutageFailsOpen() {
        store.broken = true;

        for (int attempt = 0; attempt < 10; attempt++) {
            assertThat(failureCode(EMAIL, "wrong")).isEqualTo(AuthErrorCode.LOGIN_FAILED);
        }
        assertThat(store.locked).isEmpty();
        assertThat(processor.authenticate(EMAIL, PASSWORD, CLIENT_IP).id()).isEqualTo(42L);
    }

    private AuthErrorCode failureCode(String email, String password) {
        return catchThrowableOfType(AuthException.class, () -> processor.authenticate(email, password, CLIENT_IP)).getErrorCode();
    }

    private static Member member(MemberStatus status, String passwordHash) {
        return Member.builder().id(42L).email(EMAIL).password(passwordHash).nickname("재채기탐정").role(SecurityRole.USER).status(status).build();
    }

    /** matches 호출 수를 센다. 동시 시도 테스트는 mock 대신 이것을 쓴다(호출 기록을 스레드 사이에서 확실히 센다). */
    private static class CountingPasswordEncoder implements PasswordEncoder {

        final AtomicInteger matchesCalls = new AtomicInteger();

        @Override
        public String encode(CharSequence rawPassword) {
            return DUMMY_HASH;
        }

        @Override
        public boolean matches(CharSequence rawPassword, String encodedPassword) {
            matchesCalls.incrementAndGet();
            return false;
        }
    }

    /**
     * 포트 계약대로 움직이는 메모리 저장소. 증가는 {@link ConcurrentHashMap#merge} 로 원자적이라 Redis INCR 과 같다. {@code broken} 이면 장애 때의
     * fail-open 응답(0 · 잠금 없음)을 흉내 낸다.
     */
    private static class FakeLoginAttemptStore implements LoginAttemptStorePort {

        final Map<String, Long> emailFailures = new ConcurrentHashMap<>();
        final Map<String, Long> ipAttempts = new ConcurrentHashMap<>();
        final Set<String> locked = ConcurrentHashMap.newKeySet();
        final Set<String> cleared = ConcurrentHashMap.newKeySet();
        volatile boolean broken;

        @Override
        public boolean isLocked(String email) {
            return !broken && locked.contains(email);
        }

        @Override
        public long increaseFailureCount(String email, Duration ttl) {
            return broken ? 0L : emailFailures.merge(email, 1L, Long::sum);
        }

        @Override
        public void lock(String email, Duration lockDuration) {
            if (!broken) {
                locked.add(email);
            }
        }

        @Override
        public void clearFailures(String email) {
            cleared.add(email);
            emailFailures.remove(email);
            locked.remove(email);
        }

        @Override
        public long increaseIpAttemptCount(String clientIp, Duration window) {
            return broken ? 0L : ipAttempts.merge(clientIp, 1L, Long::sum);
        }

        @Override
        public void decreaseIpAttemptCount(String clientIp) {
            if (!broken) {
                ipAttempts.computeIfPresent(clientIp, (ip, count) -> Math.max(0L, count - 1));
            }
        }
    }
}
