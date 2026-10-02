package com.sneezecast.domainlayer.auth.application.service.processor;

import com.sneezecast.domainlayer.auth.application.exception.AuthErrorCode;
import com.sneezecast.domainlayer.auth.application.exception.AuthException;
import com.sneezecast.domainlayer.auth.application.info.AuthSessionInfo;
import com.sneezecast.domainlayer.auth.application.model.SessionAccessToken;
import com.sneezecast.domainlayer.auth.application.port.out.AccessTokenBlacklistPort;
import com.sneezecast.domainlayer.auth.application.port.out.RefreshSessionStorePort;
import com.sneezecast.security.auth.jwt.JwtAuthProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * 로그아웃 · 기기 목록 · 기기 폐기. 세션을 지울 때는 그 세션이 마지막으로 받은 access token 도 남은 시간만큼 블랙리스트에 올려 기기를 바로 끊는다
 * — refresh 만 지우면 이미 받은 access 가 만료(최대 15분)까지 통한다.
 *
 * <p>세션은 키에 회원 ID 가 들어가므로 다른 회원의 세션은 구조적으로 지울 수 없다. 소유 확인을 따로 하지 않는 이유다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthSessionProcessor {

    private final RefreshSessionStorePort refreshSessionStorePort;
    private final AccessTokenBlacklistPort accessTokenBlacklistPort;
    private final JwtAuthProperties jwtAuthProperties;

    /**
     * 현재 기기 로그아웃 — 요청한 access 의 세션을 지우고 그 access 를 폐기한다. 세션 식별자가 없는 토큰이면 폐기만 한다.
     *
     * <p><b>저장소 장애는 관용 처리한다(로그만 남기고 성공).</b> 로그아웃은 사용자가 화면을 떠나려는 요청이라, 503 으로 막으면 쿠키도 지우지 못한 채
     * 로그인 상태에 갇힌다. 성공 응답은 쿠키를 지우므로 브라우저에서 refresh 가 사라지고, 서버에 남은 세션은 TTL 로 사라진다. 대가로 장애 구간의
     * access token 은 만료(15분 이하)까지 통할 수 있다.
     *
     * @param accessExpiresAt 요청 access 의 만료 시각. null 이면 access 수명 전체만큼 폐기한다
     */
    public void logout(long memberId, String sessionId, String accessTokenId, Instant accessExpiresAt) {
        Instant now = Instant.now();
        try {
            if (sessionId != null) {
                refreshSessionStorePort.delete(memberId, sessionId)
                    // 세션의 마지막 access 가 요청 토큰과 다르면(같은 세션의 다른 탭이 재발급) 그것도 끊는다.
                    .filter(stored -> !Objects.equals(stored.tokenId(), accessTokenId))
                    .ifPresent(stored -> revoke(stored, now));
            }
            if (accessTokenId != null && !accessTokenId.isBlank()) {
                Instant expiresAt = accessExpiresAt != null ? accessExpiresAt : now.plus(jwtAuthProperties.accessExpiration());
                accessTokenBlacklistPort.revoke(accessTokenId, Duration.between(now, expiresAt));
            }
        } catch (AuthException | DataAccessException exception) {
            log.error("logout revoke failed, tolerated memberId={} reason={}", memberId, exception.getClass().getSimpleName());
        }
    }

    /** 살아 있는 세션 목록 (마지막 사용 내림차순). */
    public List<AuthSessionInfo> getSessions(long memberId, String currentSessionId) {
        return refreshSessionStorePort.findAll(memberId).stream()
            .map(session -> AuthSessionInfo.builder()
                .sessionId(session.sessionId())
                .deviceLabel(session.deviceLabel())
                .createdAt(session.createdAt())
                .lastUsedAt(session.lastUsedAt())
                .current(session.sessionId().equals(currentSessionId))
                .build())
            .toList();
    }

    /** 세션 하나를 폐기한다. 멱등이다 — 이미 없는 세션이어도 성공이다. */
    public void revokeSession(long memberId, String sessionId) {
        Instant now = Instant.now();
        refreshSessionStorePort.delete(memberId, sessionId).ifPresent(accessToken -> revoke(accessToken, now));
    }

    /**
     * 현재 세션을 뺀 모든 세션을 폐기한다 ("다른 기기에서 모두 로그아웃"). 현재 세션을 알 수 없는 토큰이면 무엇을 남길지 정할 수 없어 재로그인을
     * 요구한다.
     */
    public void revokeOtherSessions(long memberId, String currentSessionId) {
        if (currentSessionId == null) {
            throw new AuthException(AuthErrorCode.REFRESH_TOKEN_EXPIRED);
        }
        revokeAll(refreshSessionStorePort.deleteAllExcept(memberId, currentSessionId));
    }

    /** 회원의 모든 세션을 폐기한다 (탈퇴 · 정지 회원의 재발급 시도 등). */
    public void revokeAllSessions(long memberId) {
        revokeAll(refreshSessionStorePort.deleteAllExcept(memberId, null));
    }

    /** 폐기한 세션들의 마지막 access token 을 블랙리스트에 올린다. */
    void revokeAll(List<SessionAccessToken> accessTokens) {
        Instant now = Instant.now();
        accessTokens.forEach(accessToken -> revoke(accessToken, now));
    }

    /**
     * 세션을 이미 지운 뒤라 블랙리스트 실패는 로그만 남긴다. 실패를 올려도 재시도로 되찾을 수 없다(access jti 는 지운 세션과 함께 사라졌다). 남는
     * 위험은 그 access 의 남은 수명(15분 이하)이다.
     */
    void revoke(SessionAccessToken accessToken, Instant now) {
        try {
            accessTokenBlacklistPort.revoke(accessToken.tokenId(), Duration.between(now, accessToken.expiresAt()));
        } catch (DataAccessException exception) {
            log.error("session access token revoke failed reason={}", exception.getClass().getSimpleName());
        }
    }
}
