package com.sneezecast.security.auth.jwt;

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
import java.time.Duration;
import java.util.Date;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;

/**
 * 토큰 발급·파싱.
 *
 * <p><b>파싱 실패는 어떤 종류든 {@link SecurityJwtException} 으로 나간다.</b> {@code JwtAuthFilter} 는 이
 * 예외만 잡아 401 을 쓰고, 여기서 새는 예외는 Spring 기본 500 이 래퍼 없이 나간다 — FE 는 그것을
 * "일시 장애 · 재시도" 로 안내하는데 재시도해도 절대 풀리지 않는 인증 오류다.
 */
@RequiredArgsConstructor
public class JwtAuthProvider {

    private static final String CLAIM_ROLE = "role";
    private final JwtAuthProperties jwtAuthProperties;

    /**
     * access token 을 발급한다. {@code scopes} 는 동의 상태에서 나온 권한 범위다 — 예를 들어 민감정보 처리 동의를
     * 마친 회원에게만 {@link SecurityScope#REPORT_WRITE} 를 넣는다. 비어 있으면 {@code scope} claim 을 싣지 않는다.
     */
    public String issueAccessToken(long memberId, SecurityRole role, Set<String> scopes) {
        ClaimsBuilder claims = Jwts.claims()
            .id(UUID.randomUUID().toString())
            .subject(String.valueOf(memberId))
            .add(CLAIM_ROLE, role);

        String scopeClaim = SecurityScope.toClaim(scopes);
        if (!scopeClaim.isEmpty()) {
            claims.add(SecurityScope.CLAIM_NAME, scopeClaim);
        }

        return issueToken(claims.build(), jwtAuthProperties.accessExpiration(), jwtAuthProperties.accessKey());
    }

    public String issueRefreshToken(long memberId) {
        return issueRefreshToken(memberId, UUID.randomUUID().toString());
    }

    /**
     * 지정한 tokenId(jti)로 refresh 토큰을 발급한다.
     * 토큰 회전 시 같은 세션 아이디를 유지해 기기별 세션 저장 키가 흔들리지 않게 하기 위한 오버로드다.
     */
    public String issueRefreshToken(long memberId, String tokenId) {
        Claims claims = Jwts.claims()
            .id(tokenId)
            .subject(String.valueOf(memberId))
            .build();

        return issueToken(claims, jwtAuthProperties.refreshExpiration(), jwtAuthProperties.refreshKey());
    }

    public RefreshTokenClaims parseRefreshToken(String refreshToken) {
        Claims payload = parseToken(refreshToken, jwtAuthProperties.refreshKey());
        return new RefreshTokenClaims(requireMemberId(payload), payload.getId());
    }

    /** refresh 토큰의 핵심 클레임. tokenId(jti)는 기기별 세션 식별자로 쓴다. */
    public record RefreshTokenClaims(long memberId, String tokenId) {
    }

    public MemberLoginActive parseAccessToken(String accessToken) {
        Claims payload = parseToken(accessToken, jwtAuthProperties.accessKey());

        return MemberLoginActive.builder()
            .memberId(requireMemberId(payload))
            .role(requireRole(payload))
            .scopes(readScopes(payload))
            .tokenId(payload.getId())
            .build();
    }

    private String issueToken(Claims claims, Duration expiration, String secretKey) {
        Date now = new Date();

        return Jwts.builder()
            .claims(claims)
            .issuedAt(now)
            .expiration(new Date(now.getTime() + expiration.toMillis()))
            .signWith(Keys.hmacShaKeyFor(secretKey.getBytes()), SIG.HS512)
            .compact();
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
                .verifyWith(Keys.hmacShaKeyFor(secretKey.getBytes()))
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
            String role = payload.get(CLAIM_ROLE, String.class);
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
}
