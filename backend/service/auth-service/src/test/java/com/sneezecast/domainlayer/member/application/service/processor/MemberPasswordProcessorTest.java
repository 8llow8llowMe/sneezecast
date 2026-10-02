package com.sneezecast.domainlayer.member.application.service.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sneezecast.domainlayer.member.application.exception.MemberErrorCode;
import com.sneezecast.domainlayer.member.application.exception.MemberException;
import com.sneezecast.domainlayer.member.application.port.out.MemberPasswordAttemptPort;
import com.sneezecast.domainlayer.member.domain.enums.MemberStatus;
import com.sneezecast.domainlayer.member.domain.enums.OAuthProvider;
import com.sneezecast.domainlayer.member.domain.model.Member;
import com.sneezecast.global.properties.LoginAttemptProperties;
import com.sneezecast.security.common.enums.SecurityRole;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

class MemberPasswordProcessorTest {

    private static final String PASSWORD = "P@ssw0rd!";
    private static final String HASH = "$2a$10$member-hash";
    private static final LoginAttemptProperties LIMITS = new LoginAttemptProperties(3, Duration.ofMinutes(10), 30, Duration.ofHours(1));
    private static final Member MEMBER = Member.builder().id(42L).email("user@example.com").password(HASH).nickname("닉네임").role(SecurityRole.USER)
        .status(MemberStatus.ACTIVE).build();

    private FakeAttemptStore store;
    private PasswordEncoder passwordEncoder;
    private MemberPasswordProcessor processor;

    @BeforeEach
    void setUp() {
        store = new FakeAttemptStore();
        passwordEncoder = mock(PasswordEncoder.class);
        when(passwordEncoder.matches(PASSWORD, HASH)).thenReturn(true);
        processor = new MemberPasswordProcessor(passwordEncoder, store, LIMITS);
    }

    @Test
    @DisplayName("현재 비밀번호가 맞으면 통과하고 실패 카운터를 지운다")
    void correctPasswordClearsCounter() {
        store.counts.put(42L, 2L);

        assertThatCode(() -> processor.verifyCurrentPassword(MEMBER, PASSWORD)).doesNotThrowAnyException();

        assertThat(store.counts).doesNotContainKey(42L);
    }

    @Test
    @DisplayName("틀리면 MEMBER_005(400) 이고, 상한째 틀리면 잠그고 MEMBER_006(429) 이다 — 잠금은 카운터 수명을 잠금 시간으로 다시 거는 것이다")
    void wrongPasswordCountsAndLocks() {
        assertThat(failure("wrong")).isEqualTo(MemberErrorCode.CURRENT_PASSWORD_MISMATCH);
        assertThat(failure("wrong")).isEqualTo(MemberErrorCode.CURRENT_PASSWORD_MISMATCH);
        assertThat(failure("wrong")).isEqualTo(MemberErrorCode.PASSWORD_CHANGE_LOCKED);

        assertThat(store.locked).containsExactly(42L);
        assertThat(store.counts).as("잠가도 카운터는 남긴다").containsEntry(42L, 3L);
    }

    @Test
    @DisplayName("상한을 넘은 시도는 BCrypt 비교 없이 MEMBER_006 이다 — 맞는 비밀번호여도 잠금 중이면 막는다")
    void overLimitSkipsBcrypt() {
        store.counts.put(42L, (long) LIMITS.maxFailureCount());

        assertThat(failure(PASSWORD)).isEqualTo(MemberErrorCode.PASSWORD_CHANGE_LOCKED);

        verify(passwordEncoder, never()).matches(anyString(), anyString());
    }

    @Test
    @DisplayName("잠긴 뒤로 시도가 계속 와도 BCrypt 비교는 상한 횟수까지만 돈다 — 카운터를 비교 전에 올리기 때문이다")
    void bcryptRunsAtMostLimitTimes() {
        for (int i = 0; i < 10; i++) {
            failure("wrong");
        }

        verify(passwordEncoder, times(LIMITS.maxFailureCount())).matches(anyString(), anyString());
    }

    @Test
    @DisplayName("카운터 저장소가 장애로 0 을 주면(fail-open) 잠그지 않고 비교 결과대로 400 이다")
    void failOpenCounterDoesNotLock() {
        store.failOpen = true;

        for (int i = 0; i < 5; i++) {
            assertThat(failure("wrong")).isEqualTo(MemberErrorCode.CURRENT_PASSWORD_MISMATCH);
        }
        assertThat(store.locked).isEmpty();
    }

    @Test
    @DisplayName("변경은 비밀번호가 있는 계정만 — 카카오로만 로그인하는 계정은 MEMBER_007(409) 이고 메시지는 비밀번호가 없다는 안내다")
    void passwordPresenceRules() {
        Member social = Member.builder().id(7L).email("kakao@example.com").password(null).nickname("닉네임").role(SecurityRole.USER)
            .provider(OAuthProvider.KAKAO).status(MemberStatus.ACTIVE).build();

        MemberException exception = catchThrowableOfType(MemberException.class, () -> processor.requirePasswordSet(social));
        assertThat(exception.getErrorCode()).isEqualTo(MemberErrorCode.PASSWORD_NOT_SET);
        assertThat(exception.getMessage()).isEqualTo("카카오로 로그인하는 계정은 비밀번호가 없습니다.");
        assertThatCode(() -> processor.requirePasswordSet(MEMBER)).doesNotThrowAnyException();
    }

    private MemberErrorCode failure(String rawPassword) {
        MemberException exception = catchThrowableOfType(MemberException.class, () -> processor.verifyCurrentPassword(MEMBER, rawPassword));
        return exception == null ? null : exception.getErrorCode();
    }

    /** INCR 의 원자성만 흉내 낸다 — 증가한 값을 그대로 돌려준다. TTL 은 흉내 내지 않는다. */
    private static class FakeAttemptStore implements MemberPasswordAttemptPort {

        private final Map<Long, Long> counts = new HashMap<>();
        private final Set<Long> locked = new HashSet<>();
        private boolean failOpen;

        @Override
        public long increaseFailureCount(long memberId, Duration ttl) {
            return failOpen ? 0L : counts.merge(memberId, 1L, Long::sum);
        }

        @Override
        public void lock(long memberId, Duration lockDuration) {
            locked.add(memberId);
        }

        @Override
        public void clearFailures(long memberId) {
            counts.remove(memberId);
        }
    }
}
