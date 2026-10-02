package com.sneezecast.domainlayer.auth.application.service.processor;

import com.sneezecast.domainlayer.auth.application.exception.AuthErrorCode;
import com.sneezecast.domainlayer.auth.application.exception.AuthException;
import com.sneezecast.domainlayer.auth.application.info.AuthTokenInfo;
import com.sneezecast.domainlayer.auth.application.model.NewRefreshSession;
import com.sneezecast.domainlayer.auth.application.model.RefreshRotation;
import com.sneezecast.domainlayer.auth.application.model.RefreshRotationResult;
import com.sneezecast.domainlayer.auth.application.model.SessionAccessToken;
import com.sneezecast.domainlayer.auth.application.port.out.RefreshSessionStorePort;
import com.sneezecast.domainlayer.auth.application.service.support.ReportScopePolicy;
import com.sneezecast.domainlayer.member.application.info.MemberConsentStatusInfo;
import com.sneezecast.domainlayer.member.application.port.out.MemberRepositoryPort;
import com.sneezecast.domainlayer.member.application.service.processor.MemberConsentProcessor;
import com.sneezecast.domainlayer.member.domain.enums.MemberStatus;
import com.sneezecast.domainlayer.member.domain.model.Member;
import com.sneezecast.global.properties.AuthSessionProperties;
import com.sneezecast.security.auth.jwt.JwtAuthProperties;
import com.sneezecast.security.auth.jwt.JwtAuthProvider;
import com.sneezecast.security.auth.jwt.JwtAuthProvider.IssuedToken;
import com.sneezecast.security.auth.jwt.JwtAuthProvider.RefreshTokenClaims;
import com.sneezecast.security.common.constant.SecurityScope;
import com.sneezecast.security.common.exception.SecurityErrorCode;
import com.sneezecast.security.common.exception.SecurityJwtException;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 토큰 발급(로그인)과 재발급(refresh 회전).
 *
 * <p>세션 ID 는 로그인 때 한 번 만들고 회전해도 유지한다 — 기기 목록 · 기기별 폐기의 기준이다. refresh 토큰은 jti 만 매번 바뀌고, 세션 저장소는
 * 현재 jti 와 직전 jti 만 기억한다. 회전 판정은 저장소가 원자적으로 한다({@link RefreshSessionStorePort#rotate}).
 *
 * <p>scope · 재동의 대기 항목은 발급할 때마다 동의 상태를 다시 읽어 계산한다 — 동의가 바뀌면 늦어도 access 만료(15분 이하) 안에 반영된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthTokenProcessor {

    private final JwtAuthProvider jwtAuthProvider;
    private final JwtAuthProperties jwtAuthProperties;
    private final RefreshSessionStorePort refreshSessionStorePort;
    private final MemberRepositoryPort memberRepositoryPort;
    private final MemberConsentProcessor memberConsentProcessor;
    private final ReportScopePolicy reportScopePolicy;
    private final AuthSessionProperties sessionProperties;
    private final AuthSessionProcessor authSessionProcessor;

    /**
     * 로그인한 회원에게 새 세션과 토큰 쌍을 발급한다. 세션 저장이 실패하면 {@code SESSION_STORE_UNAVAILABLE}(503)이고 만든 토큰은 버린다.
     *
     * @param deviceLabel "OS · 브라우저" 로 줄인 기기 이름
     */
    public AuthTokenInfo issue(Member member, String deviceLabel) {
        String sessionId = UUID.randomUUID().toString();
        MemberConsentStatusInfo consentStatus = memberConsentProcessor.currentStatus(member.id());
        Set<String> scopes = reportScopePolicy.scopesFor(consentStatus);

        IssuedToken accessToken = jwtAuthProvider.issueAccessToken(member.id(), member.role(), scopes, sessionId);
        IssuedToken refreshToken = jwtAuthProvider.issueRefreshToken(member.id(), sessionId);

        List<SessionAccessToken> evicted = refreshSessionStorePort.save(NewRefreshSession.builder()
            .memberId(member.id())
            .sessionId(sessionId)
            .refreshTokenId(refreshToken.tokenId())
            .deviceLabel(deviceLabel)
            .accessToken(toSessionAccessToken(accessToken))
            .issuedAt(Instant.now())
            .build(), jwtAuthProperties.refreshExpiration(), sessionProperties.maxDevices());
        // 기기 수 상한으로 밀려난 기기는 refresh 만 지워지면 이미 받은 access 로 만료(15분 이하)까지 쓸 수 있다 — 바로 끊는다.
        authSessionProcessor.revokeAll(evicted);

        return toInfo(member, accessToken, refreshToken, consentStatus, scopes);
    }

    /**
     * refresh 토큰으로 토큰 쌍을 다시 발급하고 refresh 를 회전한다.
     *
     * <ol>
     *   <li>쿠키 없음 · 만료 → {@code REFRESH_TOKEN_EXPIRED}, 서명 · 형식 · sid 이상 → {@code REFRESH_TOKEN_INVALID}. security-core 예외를 그대로
     *       내보내지 않는다 — 화면은 이 두 코드로 재로그인을 안내한다.</li>
     *   <li>회원이 활성이 아니면 그 회원의 모든 세션을 폐기하고 상태 코드(MEMBER_002 · 003)로 막는다.</li>
     *   <li>새 access · refresh 를 먼저 만들고 저장소가 회전에 성공했을 때만 돌려준다. 실패하면 만든 토큰은 버린다.</li>
     * </ol>
     */
    public AuthTokenInfo reissue(String refreshTokenValue) {
        if (refreshTokenValue == null || refreshTokenValue.isBlank()) {
            throw new AuthException(AuthErrorCode.REFRESH_TOKEN_EXPIRED);
        }
        RefreshTokenClaims claims = parseRefreshToken(refreshTokenValue);

        Member member = memberRepositoryPort.findById(claims.memberId()).orElse(null);
        if (member == null) {
            // 회원 행이 파기됐다 — 남은 세션은 쓸모가 없다. 다시 로그인해도 실패하므로 만료와 같은 코드로 끝낸다.
            authSessionProcessor.revokeAllSessions(claims.memberId());
            throw new AuthException(AuthErrorCode.REFRESH_TOKEN_EXPIRED);
        }
        if (member.status() != MemberStatus.ACTIVE) {
            authSessionProcessor.revokeAllSessions(member.id());
            GeneralLoginProcessor.requireActive(member);
        }

        MemberConsentStatusInfo consentStatus = memberConsentProcessor.currentStatus(member.id());
        Set<String> scopes = reportScopePolicy.scopesFor(consentStatus);
        IssuedToken accessToken = jwtAuthProvider.issueAccessToken(member.id(), member.role(), scopes, claims.sessionId());
        IssuedToken refreshToken = jwtAuthProvider.issueRefreshToken(member.id(), claims.sessionId());

        RefreshRotationResult rotation = refreshSessionStorePort.rotate(RefreshRotation.builder()
            .memberId(member.id())
            .sessionId(claims.sessionId())
            .presentedTokenId(claims.tokenId())
            .newTokenId(refreshToken.tokenId())
            .newAccessToken(toSessionAccessToken(accessToken))
            .rotatedAt(Instant.now())
            .build(), sessionProperties.rotationGrace(), jwtAuthProperties.refreshExpiration());

        return switch (rotation.outcome()) {
            case ROTATED -> toInfo(member, accessToken, refreshToken, consentStatus, scopes);
            case SESSION_NOT_FOUND -> throw new AuthException(AuthErrorCode.REFRESH_TOKEN_EXPIRED);
            case CONCURRENT_ROTATION -> throw new AuthException(AuthErrorCode.REFRESH_TOKEN_ROTATED);
            case REUSE_DETECTED -> {
                // 이미 회전된 refresh 가 다시 왔다 — 탈취된 토큰일 수 있어 그 세션을 폐기했다. 토큰 값은 남기지 않는다.
                log.warn("refresh token reuse detected, session revoked memberId={} sessionId={}", member.id(), claims.sessionId());
                if (rotation.revokedAccessToken() != null) {
                    authSessionProcessor.revoke(rotation.revokedAccessToken(), Instant.now());
                }
                throw new AuthException(AuthErrorCode.REFRESH_TOKEN_INVALID);
            }
        };
    }

    private RefreshTokenClaims parseRefreshToken(String refreshTokenValue) {
        try {
            return jwtAuthProvider.parseRefreshToken(refreshTokenValue);
        } catch (SecurityJwtException exception) {
            if (exception.getErrorCode() == SecurityErrorCode.TOKEN_EXPIRED) {
                throw new AuthException(AuthErrorCode.REFRESH_TOKEN_EXPIRED, exception);
            }
            throw new AuthException(AuthErrorCode.REFRESH_TOKEN_INVALID, exception);
        }
    }

    private AuthTokenInfo toInfo(Member member, IssuedToken accessToken, IssuedToken refreshToken, MemberConsentStatusInfo consentStatus,
        Set<String> scopes) {
        return AuthTokenInfo.builder()
            .memberId(member.id())
            .role(member.role())
            .accessToken(accessToken.value())
            .accessTokenExpiresIn(jwtAuthProperties.accessExpiration().toSeconds())
            .refreshToken(refreshToken.value())
            .pendingConsents(consentStatus.pendingRequiredConsents())
            .reportWritable(scopes.contains(SecurityScope.REPORT_WRITE))
            .build();
    }

    private static SessionAccessToken toSessionAccessToken(IssuedToken accessToken) {
        return new SessionAccessToken(accessToken.tokenId(), accessToken.expiresAt());
    }
}
