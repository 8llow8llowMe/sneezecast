package com.sneezecast.domainlayer.auth.application.service.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.sneezecast.domainlayer.auth.application.exception.AuthErrorCode;
import com.sneezecast.domainlayer.auth.application.exception.AuthException;
import com.sneezecast.domainlayer.auth.application.info.AuthSessionInfo;
import com.sneezecast.domainlayer.auth.application.model.SessionAccessToken;
import com.sneezecast.domainlayer.auth.application.port.out.AccessTokenBlacklistPort;
import com.sneezecast.domainlayer.auth.application.port.out.RefreshSessionStorePort;
import com.sneezecast.domainlayer.auth.application.port.out.query.RefreshSessionQueryResult;
import com.sneezecast.security.auth.jwt.JwtAuthProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.RedisConnectionFailureException;

class AuthSessionProcessorTest {

    private static final long MEMBER_ID = 42L;
    private static final String SESSION_ID = "session-1";
    private static final String KEY = "sneezecast-auth-session-test-key-0123456789abcdef0123456789abcdef0123456789";

    private RefreshSessionStorePort store;
    private AccessTokenBlacklistPort blacklist;
    private AuthSessionProcessor processor;

    @BeforeEach
    void setUp() {
        store = mock(RefreshSessionStorePort.class);
        blacklist = mock(AccessTokenBlacklistPort.class);
        processor = new AuthSessionProcessor(store, blacklist, new JwtAuthProperties(KEY, Duration.ofMinutes(15), KEY, Duration.ofDays(14)));
    }

    @Test
    @DisplayName("로그아웃은 현재 세션을 지우고 요청 access 를 남은 시간만큼 폐기한다 — 세션의 마지막 access 가 다르면 그것도 폐기한다")
    void logoutDeletesSessionAndRevokesAccessTokens() {
        Instant expiresAt = Instant.now().plus(Duration.ofMinutes(10));
        when(store.delete(MEMBER_ID, SESSION_ID)).thenReturn(Optional.of(new SessionAccessToken("newer-jti", expiresAt.plusSeconds(60))));

        processor.logout(MEMBER_ID, SESSION_ID, "request-jti", expiresAt);

        assertRevoked("request-jti", Duration.ofMinutes(10));
        assertRevoked("newer-jti", Duration.ofMinutes(11));
    }

    @Test
    @DisplayName("세션의 마지막 access 가 요청 토큰과 같으면 한 번만 폐기한다")
    void logoutRevokesSameTokenOnce() {
        Instant expiresAt = Instant.now().plus(Duration.ofMinutes(10));
        when(store.delete(MEMBER_ID, SESSION_ID)).thenReturn(Optional.of(new SessionAccessToken("request-jti", expiresAt)));

        processor.logout(MEMBER_ID, SESSION_ID, "request-jti", expiresAt);

        verify(blacklist, times(1)).revoke(eq("request-jti"), any());
    }

    @Test
    @DisplayName("세션 식별자가 없는 토큰이면 세션은 건드리지 않고 폐기만 한다 · 만료 시각을 모르면 access 수명 전체로 폐기한다")
    void logoutWithoutSessionIdOnlyRevokes() {
        processor.logout(MEMBER_ID, null, "request-jti", null);

        verify(store, never()).delete(anyLong(), anyString());
        assertRevoked("request-jti", Duration.ofMinutes(15));
    }

    @Test
    @DisplayName("로그아웃은 저장소 장애를 관용한다 — 세션 삭제 503 · 블랙리스트 장애 모두 예외 없이 끝난다(쿠키는 컨트롤러가 지운다)")
    void logoutToleratesStoreFailures() {
        when(store.delete(MEMBER_ID, SESSION_ID)).thenThrow(new AuthException(AuthErrorCode.SESSION_STORE_UNAVAILABLE));
        assertThatCode(() -> processor.logout(MEMBER_ID, SESSION_ID, "request-jti", Instant.now().plusSeconds(60))).doesNotThrowAnyException();

        doThrow(new RedisConnectionFailureException("down")).when(blacklist).revoke(anyString(), any());
        assertThatCode(() -> processor.logout(MEMBER_ID, null, "request-jti", Instant.now().plusSeconds(60))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("기기 목록은 저장소 순서(마지막 사용 내림차순)를 지키고 요청 세션만 current 다")
    void listsSessionsWithCurrentFlag() {
        Instant now = Instant.now();
        when(store.findAll(MEMBER_ID)).thenReturn(List.of(
            RefreshSessionQueryResult.builder().sessionId("other").deviceLabel("Mac · Chrome").createdAt(now).lastUsedAt(now).build(),
            RefreshSessionQueryResult.builder().sessionId(SESSION_ID).deviceLabel("iPhone · Safari").createdAt(now).lastUsedAt(now.minusSeconds(60))
                .build()));

        List<AuthSessionInfo> sessions = processor.getSessions(MEMBER_ID, SESSION_ID);

        assertThat(sessions).extracting(AuthSessionInfo::sessionId, AuthSessionInfo::current)
            .containsExactly(tuple("other", false), tuple(SESSION_ID, true));
        assertThat(processor.getSessions(MEMBER_ID, null)).noneMatch(AuthSessionInfo::current);
    }

    @Test
    @DisplayName("기기 폐기는 세션을 지우고 그 기기의 마지막 access 를 남은 시간만큼 폐기한다 — 이미 없는 세션이면 조용히 성공(멱등)")
    void revokeSessionBlacklistsDeviceAccessToken() {
        when(store.delete(MEMBER_ID, "device-2")).thenReturn(Optional.of(new SessionAccessToken("device-jti", Instant.now().plusSeconds(300))));
        when(store.delete(MEMBER_ID, "gone")).thenReturn(Optional.empty());

        processor.revokeSession(MEMBER_ID, "device-2");
        assertThatCode(() -> processor.revokeSession(MEMBER_ID, "gone")).doesNotThrowAnyException();

        assertRevoked("device-jti", Duration.ofSeconds(300));
        verify(blacklist, times(1)).revoke(anyString(), any());
    }

    @Test
    @DisplayName("다른 기기 모두 로그아웃은 현재 세션을 남기고 지운 세션들의 access 를 모두 폐기한다")
    void revokeOtherSessions() {
        Instant expiresAt = Instant.now().plusSeconds(300);
        when(store.deleteAllExcept(MEMBER_ID, SESSION_ID)).thenReturn(List.of(new SessionAccessToken("a", expiresAt), new SessionAccessToken("b", expiresAt)));

        processor.revokeOtherSessions(MEMBER_ID, SESSION_ID);

        verify(blacklist).revoke(eq("a"), any());
        verify(blacklist).revoke(eq("b"), any());
    }

    @Test
    @DisplayName("모든 기기 로그아웃은 회원의 모든 세션을 지우고, 지운 세션들의 access 와 요청 access 를 남은 시간만큼 폐기한다")
    void revokeAllSessionsWithRequestAccess() {
        Instant sessionExpiresAt = Instant.now().plusSeconds(300);
        when(store.deleteAllExcept(MEMBER_ID, null)).thenReturn(List.of(new SessionAccessToken("a", sessionExpiresAt)));

        processor.revokeAllSessions(MEMBER_ID, "request-jti", Instant.now().plus(Duration.ofMinutes(10)));

        verify(store).deleteAllExcept(MEMBER_ID, null);
        assertRevoked("a", Duration.ofSeconds(300));
        assertRevoked("request-jti", Duration.ofMinutes(10));
    }

    @Test
    @DisplayName("요청 access 가 지운 세션의 마지막 access 와 같으면 한 번만 폐기하고, 만료 시각을 모르면 access 수명 전체로 폐기한다")
    void revokeAllSessionsRequestAccessEdgeCases() {
        Instant expiresAt = Instant.now().plusSeconds(300);
        when(store.deleteAllExcept(MEMBER_ID, null)).thenReturn(List.of(new SessionAccessToken("request-jti", expiresAt)));
        processor.revokeAllSessions(MEMBER_ID, "request-jti", expiresAt);
        verify(blacklist, times(1)).revoke(eq("request-jti"), any());

        when(store.deleteAllExcept(MEMBER_ID, null)).thenReturn(List.of());
        processor.revokeAllSessions(MEMBER_ID, "other-jti", null);
        assertRevoked("other-jti", Duration.ofMinutes(15));
    }

    @Test
    @DisplayName("모든 기기 로그아웃의 세션 삭제 장애는 AUTH_017 로 올린다 — 요청 access 도 올리지 않는다")
    void revokeAllSessionsStoreFailure() {
        when(store.deleteAllExcept(MEMBER_ID, null)).thenThrow(new AuthException(AuthErrorCode.SESSION_STORE_UNAVAILABLE));

        assertThatThrownBy(() -> processor.revokeAllSessions(MEMBER_ID, "request-jti", Instant.now().plusSeconds(60)))
            .isInstanceOfSatisfying(AuthException.class, e -> assertThat(e.getErrorCode()).isEqualTo(AuthErrorCode.SESSION_STORE_UNAVAILABLE));
        verifyNoInteractions(blacklist);
    }

    @Test
    @DisplayName("현재 세션을 모르는 토큰으로 다른 기기 모두 로그아웃을 하면 AUTH_014 — 무엇을 남길지 정할 수 없다")
    void revokeOtherSessionsRequiresCurrentSession() {
        assertThatThrownBy(() -> processor.revokeOtherSessions(MEMBER_ID, null))
            .isInstanceOfSatisfying(AuthException.class, e -> assertThat(e.getErrorCode()).isEqualTo(AuthErrorCode.REFRESH_TOKEN_EXPIRED));
        verifyNoInteractions(store);
    }

    @Test
    @DisplayName("세션 삭제 장애는 503 으로 올린다 · 삭제 뒤 블랙리스트 장애는 로그만 남긴다(지운 세션의 jti 는 재시도로 되찾을 수 없다)")
    void revokeSessionFailureModes() {
        when(store.delete(MEMBER_ID, "down")).thenThrow(new AuthException(AuthErrorCode.SESSION_STORE_UNAVAILABLE));
        assertThatThrownBy(() -> processor.revokeSession(MEMBER_ID, "down")).isInstanceOf(AuthException.class);

        when(store.delete(MEMBER_ID, "device-2")).thenReturn(Optional.of(new SessionAccessToken("device-jti", Instant.now().plusSeconds(300))));
        doThrow(new RedisConnectionFailureException("down")).when(blacklist).revoke(anyString(), any());
        assertThatCode(() -> processor.revokeSession(MEMBER_ID, "device-2")).doesNotThrowAnyException();
    }

    private void assertRevoked(String tokenId, Duration expectedRemaining) {
        ArgumentCaptor<Duration> remaining = ArgumentCaptor.forClass(Duration.class);
        verify(blacklist).revoke(eq(tokenId), remaining.capture());
        assertThat(remaining.getValue().toMillis()).isCloseTo(expectedRemaining.toMillis(), within(5_000L));
    }
}
