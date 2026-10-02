package com.sneezecast.domainlayer.auth.application.service.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.sneezecast.domainlayer.auth.application.exception.AuthErrorCode;
import com.sneezecast.domainlayer.auth.application.exception.AuthException;
import com.sneezecast.domainlayer.auth.application.info.AuthTokenInfo;
import com.sneezecast.domainlayer.auth.application.model.NewRefreshSession;
import com.sneezecast.domainlayer.auth.application.model.RefreshRotation;
import com.sneezecast.domainlayer.auth.application.model.RefreshRotationResult;
import com.sneezecast.domainlayer.auth.application.model.RefreshRotationResult.Outcome;
import com.sneezecast.domainlayer.auth.application.model.SessionAccessToken;
import com.sneezecast.domainlayer.auth.application.port.out.AccessTokenBlacklistPort;
import com.sneezecast.domainlayer.auth.application.port.out.RefreshSessionStorePort;
import com.sneezecast.domainlayer.auth.application.service.support.ReportScopePolicy;
import com.sneezecast.domainlayer.member.application.exception.MemberErrorCode;
import com.sneezecast.domainlayer.member.application.exception.MemberException;
import com.sneezecast.domainlayer.member.application.info.MemberConsentStatusInfo;
import com.sneezecast.domainlayer.member.application.port.out.MemberRepositoryPort;
import com.sneezecast.domainlayer.member.application.service.processor.MemberConsentProcessor;
import com.sneezecast.domainlayer.member.domain.enums.ConsentType;
import com.sneezecast.domainlayer.member.domain.enums.MemberStatus;
import com.sneezecast.domainlayer.member.domain.model.Member;
import com.sneezecast.global.properties.AuthSessionProperties;
import com.sneezecast.security.auth.jwt.JwtAuthProperties;
import com.sneezecast.security.auth.jwt.JwtAuthProvider;
import com.sneezecast.security.auth.jwt.JwtAuthProvider.RefreshTokenClaims;
import com.sneezecast.security.common.constant.SecurityScope;
import com.sneezecast.security.common.dto.MemberLoginActive;
import com.sneezecast.security.common.enums.SecurityRole;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.RedisConnectionFailureException;

class AuthTokenProcessorTest {

    private static final long MEMBER_ID = 42L;
    private static final String ACCESS_KEY = "sneezecast-auth-token-test-access-key-0123456789abcdef0123456789abcdef01234567";
    private static final String REFRESH_KEY = "sneezecast-auth-token-test-refresh-key-0123456789abcdef0123456789abcdef0123456";
    private static final JwtAuthProperties JWT = new JwtAuthProperties(ACCESS_KEY, Duration.ofMinutes(15), REFRESH_KEY, Duration.ofDays(14));
    private static final AuthSessionProperties SESSION = new AuthSessionProperties(5, Duration.ofSeconds(10));
    private static final MemberConsentStatusInfo ALL_AGREED = new MemberConsentStatusInfo(List.of(), true);

    private final JwtAuthProvider jwtAuthProvider = new JwtAuthProvider(JWT);
    private RefreshSessionStorePort store;
    private AccessTokenBlacklistPort blacklist;
    private MemberRepositoryPort memberRepositoryPort;
    private MemberConsentProcessor memberConsentProcessor;
    private AuthTokenProcessor processor;

    @BeforeEach
    void setUp() {
        store = mock(RefreshSessionStorePort.class);
        blacklist = mock(AccessTokenBlacklistPort.class);
        memberRepositoryPort = mock(MemberRepositoryPort.class);
        memberConsentProcessor = mock(MemberConsentProcessor.class);
        when(memberConsentProcessor.currentStatus(MEMBER_ID)).thenReturn(ALL_AGREED);
        when(memberRepositoryPort.findById(MEMBER_ID)).thenReturn(Optional.of(member(MemberStatus.ACTIVE)));
        processor = new AuthTokenProcessor(jwtAuthProvider, JWT, store, memberRepositoryPort, memberConsentProcessor, new ReportScopePolicy(), SESSION,
            new AuthSessionProcessor(store, blacklist, JWT));
    }

    @Test
    @DisplayName("로그인 발급은 새 세션을 저장한다 — refresh 원문이 아니라 jti, access 의 jti · 만료, 기기 이름, TTL = refresh 만료, 기기 상한")
    void issueSavesSession() {
        AuthTokenInfo info = processor.issue(member(MemberStatus.ACTIVE), "iPhone · Safari");

        ArgumentCaptor<NewRefreshSession> saved = ArgumentCaptor.forClass(NewRefreshSession.class);
        verify(store).save(saved.capture(), eq(JWT.refreshExpiration()), eq(5));
        MemberLoginActive access = jwtAuthProvider.parseAccessToken(info.accessToken());
        RefreshTokenClaims refresh = jwtAuthProvider.parseRefreshToken(info.refreshToken());

        NewRefreshSession session = saved.getValue();
        assertThat(session.memberId()).isEqualTo(MEMBER_ID);
        assertThat(UUID.fromString(session.sessionId())).isNotNull();
        assertThat(session.refreshTokenId()).isEqualTo(refresh.tokenId());
        assertThat(session.deviceLabel()).isEqualTo("iPhone · Safari");
        assertThat(session.accessToken()).isEqualTo(new SessionAccessToken(access.tokenId(), access.expiresAt()));
        assertThat(session.issuedAt()).isBeforeOrEqualTo(Instant.now());
        assertThat(access.sessionId()).isEqualTo(session.sessionId());
        assertThat(refresh.sessionId()).isEqualTo(session.sessionId());
        assertThat(access.scopes()).containsExactly(SecurityScope.REPORT_WRITE);

        assertThat(info.memberId()).isEqualTo(MEMBER_ID);
        assertThat(info.role()).isEqualTo(SecurityRole.USER);
        assertThat(info.accessTokenExpiresIn()).isEqualTo(900);
        assertThat(info.pendingConsents()).isEmpty();
        assertThat(info.reportWritable()).isTrue();
    }

    @Test
    @DisplayName("기기 수 상한으로 밀려난 세션의 마지막 access 를 남은 시간만큼 블랙리스트에 올린다 — 밀려난 기기를 바로 끊는다")
    void issueRevokesEvictedSessionAccessTokens() {
        Instant evictedExpiresAt = Instant.now().plus(Duration.ofMinutes(5));
        when(store.save(any(), eq(JWT.refreshExpiration()), eq(5))).thenReturn(List.of(new SessionAccessToken("evicted-access", evictedExpiresAt)));

        processor.issue(member(MemberStatus.ACTIVE), "Mac · Chrome");

        ArgumentCaptor<Duration> remaining = ArgumentCaptor.forClass(Duration.class);
        verify(blacklist).revoke(eq("evicted-access"), remaining.capture());
        assertThat(remaining.getValue()).isBetween(Duration.ofMinutes(4), Duration.ofMinutes(5));
    }

    @Test
    @DisplayName("밀려난 세션 블랙리스트가 실패해도 로그인은 성공한다 — 다른 폐기 경로처럼 로그만 남긴다")
    void issueToleratesEvictionBlacklistFailure() {
        when(store.save(any(), eq(JWT.refreshExpiration()), eq(5))).thenReturn(List.of(new SessionAccessToken("evicted-access", Instant.now().plusSeconds(60))));
        doThrow(new RedisConnectionFailureException("down")).when(blacklist).revoke(anyString(), any());

        assertThat(processor.issue(member(MemberStatus.ACTIVE), "Mac · Chrome").accessToken()).isNotBlank();
    }

    @Test
    @DisplayName("재동의할 필수 항목이 있으면 로그인은 되고 응답으로 알리며, report:write 는 싣지 않는다")
    void issueWithPendingConsent() {
        when(memberConsentProcessor.currentStatus(MEMBER_ID)).thenReturn(new MemberConsentStatusInfo(List.of(ConsentType.PRIVACY_POLICY), true));

        AuthTokenInfo info = processor.issue(member(MemberStatus.ACTIVE), "Mac · Chrome");

        assertThat(info.pendingConsents()).containsExactly(ConsentType.PRIVACY_POLICY);
        assertThat(info.reportWritable()).isFalse();
        assertThat(jwtAuthProvider.parseAccessToken(info.accessToken()).scopes()).isEmpty();
    }

    @Test
    @DisplayName("세션 저장 장애는 503 AUTH_017 로 올라가고 토큰을 내주지 않는다")
    void issueStoreFailure() {
        doThrow(new AuthException(AuthErrorCode.SESSION_STORE_UNAVAILABLE)).when(store).save(any(), any(), eq(5));

        assertThatThrownBy(() -> processor.issue(member(MemberStatus.ACTIVE), "Mac · Chrome"))
            .isInstanceOfSatisfying(AuthException.class, e -> assertThat(e.getErrorCode()).isEqualTo(AuthErrorCode.SESSION_STORE_UNAVAILABLE));
    }

    @Test
    @DisplayName("쿠키가 없거나 비었으면 AUTH_014 이고 저장소 · 회원을 보지 않는다")
    void reissueWithoutCookie() {
        assertThat(reissueFailure(null)).isEqualTo(AuthErrorCode.REFRESH_TOKEN_EXPIRED);
        assertThat(reissueFailure(" ")).isEqualTo(AuthErrorCode.REFRESH_TOKEN_EXPIRED);
        verifyNoInteractions(store, memberRepositoryPort);
    }

    @Test
    @DisplayName("만료된 refresh 는 AUTH_014, 서명 · 형식이 틀리면 AUTH_015 — security-core 예외를 그대로 내보내지 않는다")
    void reissueMapsTokenErrors() {
        JwtAuthProvider expiredIssuer = new JwtAuthProvider(new JwtAuthProperties(ACCESS_KEY, Duration.ofMinutes(15), REFRESH_KEY, Duration.ofSeconds(-60)));
        String accessAsRefresh = jwtAuthProvider.issueAccessToken(MEMBER_ID, SecurityRole.USER, Set.of(), "session-1").value();

        assertThat(reissueFailure(expiredIssuer.issueRefreshToken(MEMBER_ID, "session-1").value())).isEqualTo(AuthErrorCode.REFRESH_TOKEN_EXPIRED);
        assertThat(reissueFailure("not-a-jwt")).isEqualTo(AuthErrorCode.REFRESH_TOKEN_INVALID);
        assertThat(reissueFailure(accessAsRefresh)).as("access 키로 서명된 토큰").isEqualTo(AuthErrorCode.REFRESH_TOKEN_INVALID);
        verifyNoInteractions(store);
    }

    @Test
    @DisplayName("회전에 성공하면 같은 세션으로 새 access · refresh 를 돌려준다 — 저장소에는 제시 jti · 새 jti · 새 access · 유예 · TTL 을 넘긴다")
    void reissueRotates() {
        String presented = jwtAuthProvider.issueRefreshToken(MEMBER_ID, "session-1").value();
        RefreshTokenClaims presentedClaims = jwtAuthProvider.parseRefreshToken(presented);
        when(store.rotate(any(), eq(SESSION.rotationGrace()), eq(JWT.refreshExpiration()))).thenReturn(RefreshRotationResult.of(Outcome.ROTATED));
        when(memberConsentProcessor.currentStatus(MEMBER_ID)).thenReturn(new MemberConsentStatusInfo(List.of(), false));

        AuthTokenInfo info = processor.reissue(presented);

        ArgumentCaptor<RefreshRotation> rotation = ArgumentCaptor.forClass(RefreshRotation.class);
        verify(store).rotate(rotation.capture(), eq(SESSION.rotationGrace()), eq(JWT.refreshExpiration()));
        RefreshTokenClaims newRefresh = jwtAuthProvider.parseRefreshToken(info.refreshToken());
        MemberLoginActive newAccess = jwtAuthProvider.parseAccessToken(info.accessToken());
        assertThat(rotation.getValue().memberId()).isEqualTo(MEMBER_ID);
        assertThat(rotation.getValue().sessionId()).isEqualTo("session-1");
        assertThat(rotation.getValue().presentedTokenId()).isEqualTo(presentedClaims.tokenId());
        assertThat(rotation.getValue().newTokenId()).isEqualTo(newRefresh.tokenId()).isNotEqualTo(presentedClaims.tokenId());
        assertThat(rotation.getValue().newAccessToken()).isEqualTo(new SessionAccessToken(newAccess.tokenId(), newAccess.expiresAt()));
        assertThat(newRefresh.sessionId()).as("회전해도 세션은 유지").isEqualTo("session-1");
        assertThat(newAccess.sessionId()).isEqualTo("session-1");
        assertThat(newAccess.scopes()).as("동의 상태를 다시 읽어 scope 를 계산한다").isEmpty();
        assertThat(info.reportWritable()).isFalse();
    }

    @Test
    @DisplayName("세션이 없으면(0) AUTH_014, 동시 재발급 경합(2)이면 409 AUTH_016 — 둘 다 블랙리스트를 건드리지 않는다")
    void reissueNotFoundAndConcurrent() {
        String presented = jwtAuthProvider.issueRefreshToken(MEMBER_ID, "session-1").value();

        when(store.rotate(any(), any(), any())).thenReturn(RefreshRotationResult.of(Outcome.SESSION_NOT_FOUND));
        assertThat(reissueFailure(presented)).isEqualTo(AuthErrorCode.REFRESH_TOKEN_EXPIRED);

        when(store.rotate(any(), any(), any())).thenReturn(RefreshRotationResult.of(Outcome.CONCURRENT_ROTATION));
        assertThat(reissueFailure(presented)).isEqualTo(AuthErrorCode.REFRESH_TOKEN_ROTATED);

        verifyNoInteractions(blacklist);
    }

    @Test
    @DisplayName("재사용 감지(3)면 AUTH_015 이고, 폐기된 세션의 마지막 access 를 남은 시간만큼 블랙리스트에 올린다")
    void reissueReuseRevokesAccessToken() {
        String presented = jwtAuthProvider.issueRefreshToken(MEMBER_ID, "session-1").value();
        Instant accessExpiresAt = Instant.now().plus(Duration.ofMinutes(5));
        when(store.rotate(any(), any(), any()))
            .thenReturn(new RefreshRotationResult(Outcome.REUSE_DETECTED, new SessionAccessToken("stolen-session-access", accessExpiresAt)));

        assertThat(reissueFailure(presented)).isEqualTo(AuthErrorCode.REFRESH_TOKEN_INVALID);

        ArgumentCaptor<Duration> remaining = ArgumentCaptor.forClass(Duration.class);
        verify(blacklist).revoke(eq("stolen-session-access"), remaining.capture());
        assertThat(remaining.getValue()).isBetween(Duration.ofMinutes(4), Duration.ofMinutes(5));
    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({"WITHDRAWN, WITHDRAWN_MEMBER", "SUSPENDED, SUSPENDED_MEMBER"})
    @DisplayName("탈퇴 · 정지 회원의 재발급은 그 회원의 모든 세션을 지우고 각 access 를 폐기한 뒤 상태 코드로 막는다 — 회전하지 않는다")
    void reissueInactiveMemberRevokesAllSessions(MemberStatus status, MemberErrorCode expected) {
        when(memberRepositoryPort.findById(MEMBER_ID)).thenReturn(Optional.of(member(status)));
        when(store.deleteAllExcept(MEMBER_ID, null)).thenReturn(List.of(new SessionAccessToken("device-access", Instant.now().plusSeconds(300))));
        String presented = jwtAuthProvider.issueRefreshToken(MEMBER_ID, "session-1").value();

        assertThatThrownBy(() -> processor.reissue(presented))
            .isInstanceOfSatisfying(MemberException.class, e -> assertThat(e.getErrorCode()).isEqualTo(expected));
        verify(blacklist).revoke(eq("device-access"), any());
        verify(store, never()).rotate(any(), any(), any());
    }

    @Test
    @DisplayName("회원 행이 없으면 남은 세션을 지우고 AUTH_014 다")
    void reissueMissingMember() {
        when(memberRepositoryPort.findById(MEMBER_ID)).thenReturn(Optional.empty());
        when(store.deleteAllExcept(MEMBER_ID, null)).thenReturn(List.of());

        assertThat(reissueFailure(jwtAuthProvider.issueRefreshToken(MEMBER_ID, "session-1").value())).isEqualTo(AuthErrorCode.REFRESH_TOKEN_EXPIRED);
        verify(store).deleteAllExcept(MEMBER_ID, null);
    }

    @Test
    @DisplayName("회전 중 저장소 장애는 503 AUTH_017 이다")
    void reissueStoreFailure() {
        when(store.rotate(any(), any(), any())).thenThrow(new AuthException(AuthErrorCode.SESSION_STORE_UNAVAILABLE));

        assertThat(reissueFailure(jwtAuthProvider.issueRefreshToken(MEMBER_ID, "session-1").value())).isEqualTo(AuthErrorCode.SESSION_STORE_UNAVAILABLE);
        verify(blacklist, never()).revoke(anyString(), any());
    }

    private AuthErrorCode reissueFailure(String refreshToken) {
        return catchThrowableOfType(AuthException.class, () -> processor.reissue(refreshToken)).getErrorCode();
    }

    private static Member member(MemberStatus status) {
        return Member.builder().id(MEMBER_ID).email("user@example.com").password("hash").nickname("재채기탐정").role(SecurityRole.USER).status(status).build();
    }
}
