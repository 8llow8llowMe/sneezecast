package com.sneezecast.security.auth.jwt;

import com.sneezecast.security.common.constant.JwtClaimNames;
import com.sneezecast.security.common.constant.SecurityScope;
import com.sneezecast.security.common.dto.MemberLoginActive;
import com.sneezecast.security.common.enums.SecurityRole;
import com.sneezecast.security.common.exception.SecurityErrorCode;
import com.sneezecast.security.common.exception.SecurityJwtException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ClaimsBuilder;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.Jwts.SIG;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SignatureException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.util.Assert;

/**
 * 토큰 발급·파싱.
 *
 * <p><b>파싱 실패는 어떤 종류든 {@link SecurityJwtException} 으로 나간다.</b> {@code JwtAuthFilter} 는 이
 * 예외만 잡아 401 을 쓰고, 여기서 새는 예외는 Spring 기본 500 이 래퍼 없이 나간다 — FE 는 그것을
 * "일시 장애 · 재시도" 로 안내하는데 재시도해도 절대 풀리지 않는 인증 오류다.
 */
@RequiredArgsConstructor
public class JwtAuthProvider {

    private final JwtAuthProperties jwtAuthProperties;

    /**
     * access token 을 발급한다. {@code scopes} 는 동의 상태에서 나온 권한 범위다 — 예를 들어 민감정보 처리 동의를
     * 마친 회원에게만 {@link SecurityScope#REPORT_WRITE} 를 넣는다. 비어 있으면 {@code scope} claim 을 싣지 않는다.
     *
     * @param sessionId 로그인 세션(기기) 식별자. {@link JwtClaimNames#SESSION_ID} claim 으로 싣는다. null 이면 싣지 않는다
     * @return 토큰과 그 jti · 만료 시각 — 세션 저장소가 기기의 현재 access token 을 기억했다가 기기를 끊을 때 블랙리스트에 올린다
     */
    public IssuedToken issueAccessToken(long memberId, SecurityRole role, Set<String> scopes, String sessionId) {
        ClaimsBuilder claims = Jwts.claims()
            .id(UUID.randomUUID().toString())
            .subject(String.valueOf(memberId))
            .add(JwtClaimNames.ROLE, role);

        String scopeClaim = SecurityScope.toClaim(scopes);
        if (!scopeClaim.isEmpty()) {
            claims.add(SecurityScope.CLAIM_NAME, scopeClaim);
        }
        if (sessionId != null) {
            Assert.hasText(sessionId, "sessionId must not be blank");
            claims.add(JwtClaimNames.SESSION_ID, sessionId);
        }

        return issueToken(claims.build(), jwtAuthProperties.accessExpiration(), jwtAuthProperties.accessKey());
    }

    /**
     * refresh 토큰을 발급한다. 세션 식별자({@code sid})는 로그인 때 정해 회전해도 그대로 두고, <b>jti 는 발급마다 새 UUID</b> 다.
     *
     * <p>같은 jti 를 다시 쓰지 않는 이유 — {@code iat} · {@code exp} 가 초 단위라, 같은 초 안에 같은 jti 로 다시 발급하면 바이트까지 같은
     * 토큰이 나온다. 그러면 회전 전후 토큰을 구별할 수 없어 "이미 회전된 토큰의 재사용" 을 잡지 못한다.
     */
    public IssuedToken issueRefreshToken(long memberId, String sessionId) {
        Assert.hasText(sessionId, "sessionId must not be blank");
        Claims claims = Jwts.claims()
            .id(UUID.randomUUID().toString())
            .subject(String.valueOf(memberId))
            .add(JwtClaimNames.SESSION_ID, sessionId)
            .build();

        return issueToken(claims, jwtAuthProperties.refreshExpiration(), jwtAuthProperties.refreshKey());
    }

    /**
     * refresh 토큰을 파싱한다. 세션 식별자가 없거나 비었거나 문자열이 아니면 {@code TOKEN_INVALID} 다 — 회전할 세션을 찾을 수 없는 토큰이다.
     * jti 가 없어도 같다 (재사용 판정의 기준값이다).
     */
    public RefreshTokenClaims parseRefreshToken(String refreshToken) {
        Claims payload = parseToken(refreshToken, jwtAuthProperties.refreshKey());
        String sessionId = readSessionId(payload);
        if (sessionId == null || payload.getId() == null || payload.getId().isBlank()) {
            throw new SecurityJwtException(SecurityErrorCode.TOKEN_INVALID);
        }
        return new RefreshTokenClaims(requireMemberId(payload), sessionId, payload.getId());
    }

    /**
     * refresh 토큰의 핵심 클레임.
     *
     * @param sessionId 로그인 세션(기기) 식별자 — 회전해도 그대로다
     * @param tokenId   이 토큰의 jti — 발급마다 바뀐다. 세션 저장소의 현재 jti 와 비교해 재사용을 잡는다
     */
    public record RefreshTokenClaims(long memberId, String sessionId, String tokenId) {
    }

    /**
     * 발급한 토큰과 그 메타데이터.
     *
     * @param tokenId   jti
     * @param expiresAt 토큰에 실린 exp — 초 단위로 잘린 값이라 파싱 결과의 만료 시각과 같다
     */
    public record IssuedToken(String value, String tokenId, Instant expiresAt) {

        /** 토큰 원문은 로그 · 예외 메시지에 흘리지 않는다. jti · 만료는 식별 · 진단용이라 남긴다. */
        @Override
        public String toString() {
            return "IssuedToken[value=****, tokenId=" + tokenId + ", expiresAt=" + expiresAt + "]";
        }
    }

    public MemberLoginActive parseAccessToken(String accessToken) {
        Claims payload = parseToken(accessToken, jwtAuthProperties.accessKey());

        return MemberLoginActive.builder()
            .memberId(requireMemberId(payload))
            .role(requireRole(payload))
            .scopes(readScopes(payload))
            .tokenId(payload.getId())
            .expiresAt(payload.getExpiration() == null ? null : payload.getExpiration().toInstant())
            .sessionId(readSessionId(payload))
            .build();
    }

    private IssuedToken issueToken(Claims claims, Duration expiration, String secretKey) {
        Date now = new Date();
        // JWT 는 시각을 초 단위로 싣는다. 반환하는 만료 시각을 같은 값으로 잘라 두어 파싱 결과와 어긋나지 않게 한다.
        Instant expiresAt = Instant.ofEpochSecond((now.getTime() + expiration.toMillis()) / 1000);

        String token = Jwts.builder()
            .claims(claims)
            .issuedAt(now)
            .expiration(Date.from(expiresAt))
            .signWith(Keys.hmacShaKeyFor(secretKey.getBytes(StandardCharsets.UTF_8)), SIG.HS512)
            .compact();
        return new IssuedToken(token, claims.getId(), expiresAt);
    }

    /**
     * 만료와 서명 불일치만 코드를 나누고 나머지는 전부 {@code TOKEN_INVALID} 다 — 형식 오류(MalformedJwt),
     * 서명부를 디코딩할 수 없는 길이(jjwt 0.13 은 UnsupportedJwt 로 던진다), 지원하지 않는 alg, 빈 문자열
     * (IllegalArgument) 등. 화면이 갈라 다룰 이유가 없고, 어느 검사에 걸렸는지는 cause 로 로그에 남는다.
     * {@code JwtException} 이 jjwt 예외 전체의 부모라 새 예외 종류가 생겨도 여기서 막힌다.
     */
    private Claims parseToken(String token, String secretKey) {
        try {
            return Jwts.parser()
                .verifyWith(Keys.hmacShaKeyFor(secretKey.getBytes(StandardCharsets.UTF_8)))
                .build()
                .parseSignedClaims(token)
                .getPayload();
        } catch (ExpiredJwtException e) {
            throw new SecurityJwtException(SecurityErrorCode.TOKEN_EXPIRED, e);
        } catch (SignatureException e) {
            throw new SecurityJwtException(SecurityErrorCode.TOKEN_SIGNATURE_INVALID, e);
        } catch (JwtException | IllegalArgumentException e) {
            throw new SecurityJwtException(SecurityErrorCode.TOKEN_INVALID, e);
        }
    }

    /**
     * 서명이 맞아도 클레임이 발급 규약과 다르면 서버 오류가 아니라 잘못된 토큰이다. 키가 새지 않으면 만들 수 없는
     * 토큰이지만, 만들 수 있게 됐을 때 500 으로 보이게 두면 그 사실이 서버 장애로 위장된다.
     */
    private long requireMemberId(Claims payload) {
        try {
            return Long.parseLong(payload.getSubject());
        } catch (NumberFormatException e) {
            throw new SecurityJwtException(SecurityErrorCode.TOKEN_INVALID, e);
        }
    }

    private SecurityRole requireRole(Claims payload) {
        try {
            String role = payload.get(JwtClaimNames.ROLE, String.class);
            if (role == null) {
                throw new SecurityJwtException(SecurityErrorCode.TOKEN_INVALID);
            }
            return SecurityRole.from(role);
        } catch (JwtException | IllegalArgumentException e) {
            // role 이 문자열이 아니면 jjwt 가 RequiredTypeException 을, 모르는 값이면 valueOf 가 IllegalArgument 를 던진다.
            throw new SecurityJwtException(SecurityErrorCode.TOKEN_INVALID, e);
        }
    }

    /**
     * scope 는 role 과 달리 <b>없어도 된다</b> — 동의 전 회원의 토큰에는 싣지 않으므로 빈 집합이 정상이다.
     * 다만 있는데 문자열이 아니면 발급 규약과 다른 토큰이므로 {@code TOKEN_INVALID} 로 거부한다.
     */
    private Set<String> readScopes(Claims payload) {
        try {
            return SecurityScope.fromClaim(payload.get(SecurityScope.CLAIM_NAME, String.class));
        } catch (JwtException e) {
            // scope 가 문자열이 아니면 jjwt 가 RequiredTypeException(JwtException 하위)을 던진다.
            throw new SecurityJwtException(SecurityErrorCode.TOKEN_INVALID, e);
        }
    }

    /**
     * sid 는 access 에서는 <b>없어도 된다</b>(null). 있는데 문자열이 아니거나 비어 있으면 발급 규약과 다른 토큰이라 {@code TOKEN_INVALID} 다.
     * refresh 는 호출자가 null 도 거부한다.
     */
    private String readSessionId(Claims payload) {
        try {
            String sessionId = payload.get(JwtClaimNames.SESSION_ID, String.class);
            if (sessionId != null && sessionId.isBlank()) {
                throw new SecurityJwtException(SecurityErrorCode.TOKEN_INVALID);
            }
            return sessionId;
        } catch (JwtException e) {
            // sid 가 문자열이 아니면 jjwt 가 RequiredTypeException(JwtException 하위)을 던진다.
            throw new SecurityJwtException(SecurityErrorCode.TOKEN_INVALID, e);
        }
    }
}
