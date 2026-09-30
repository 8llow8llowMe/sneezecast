package com.sneezecast.security.auth.jwt;

import com.sneezecast.security.auth.blacklist.AccessTokenBlacklistVerifier;
import com.sneezecast.security.common.dto.MemberLoginActive;
import com.sneezecast.security.common.exception.SecurityErrorCode;
import com.sneezecast.security.common.exception.SecurityJwtException;
import com.sneezecast.security.common.handler.AuthenticationFailureHandler;
import com.sneezecast.security.common.jwt.JwtAuthentication;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";
    private final JwtAuthProvider jwtAuthProvider;
    private final AuthenticationFailureHandler failureHandler;
    // 구현 빈이 없으면 null — 블랙리스트 검증 없이 기존 동작을 유지한다.
    private final AccessTokenBlacklistVerifier blacklistVerifier;

    @Override
    protected void doFilterInternal(
        HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {

        String accessToken = getJwtFrom(request);

        if (StringUtils.hasText(accessToken)) {
            try {
                MemberLoginActive member = jwtAuthProvider.parseAccessToken(accessToken);
                validateNotRevoked(member.tokenId());
                SecurityContextHolder.getContext()
                    .setAuthentication(JwtAuthentication.authenticated(member));
            } catch (SecurityJwtException e) {
                SecurityContextHolder.clearContext();

                if (failureHandler.handleAuthenticationFailure(request, response, e)) {
                    return;
                }
            }
        }

        filterChain.doFilter(request, response);
    }

    private void validateNotRevoked(String tokenId) {
        if (blacklistVerifier == null) {
            return;
        }

        // jti가 없는 토큰은 revoke가 영구히 불가능하므로 fail-closed로 거부한다. (정상 발급 토큰은 항상 jti 포함)
        if (!StringUtils.hasText(tokenId)) {
            throw new SecurityJwtException(SecurityErrorCode.TOKEN_INVALID);
        }

        if (blacklistVerifier.isRevoked(tokenId)) {
            throw new SecurityJwtException(SecurityErrorCode.TOKEN_REVOKED);
        }
    }

    private String getJwtFrom(HttpServletRequest request) {
        String bearerToken = request.getHeader(HttpHeaders.AUTHORIZATION);

        // scheme 은 대소문자를 가리지 않는다 (RFC 7235). 게이트웨이 · Resource Server 와 판정을 맞춘다.
        if (StringUtils.hasText(bearerToken) && bearerToken.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return bearerToken.substring(BEARER_PREFIX.length());
        }

        return null;
    }
}
