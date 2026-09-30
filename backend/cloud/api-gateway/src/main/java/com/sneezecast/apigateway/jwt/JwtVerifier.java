package com.sneezecast.apigateway.jwt;

import com.sneezecast.apigateway.jwt.properties.JwtVerificationProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 게이트웨이의 1차 검증 — 서명과 만료만 본다.
 *
 * <p>{@code role} · {@code scope} claim 은 해석하지 않는다. claim 이 규약(security-core {@code JwtAuthProvider})과
 * 다른 토큰의 거부와 인가 판정은 각 서비스의 Resource Server 가 맡는다. 여기서 역할 목록을 복사해 두면 역할이 늘 때
 * 게이트웨이만 뒤처져 멀쩡한 토큰을 막게 된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtVerifier {

    private final JwtVerificationProperties jwtVerificationProperties;

    /**
     * @throws JwtException             jjwt 의 검증 실패 (만료 · 서명 · 형식 · 미서명 토큰 …)
     * @throws IllegalArgumentException 빈 토큰
     */
    public Claims validateAndGetClaims(String token) {
        try {
            return Jwts.parser()
                .verifyWith(Keys.hmacShaKeyFor(jwtVerificationProperties.accessKey().getBytes()))
                .build()
                .parseSignedClaims(token)
                .getPayload();
        } catch (JwtException | IllegalArgumentException e) {
            // 예외 메시지에는 토큰 조각이 섞일 수 있어 종류만 남긴다.
            log.warn("jwt verification failed reason={}", e.getClass().getSimpleName());
            throw e;
        }
    }
}
