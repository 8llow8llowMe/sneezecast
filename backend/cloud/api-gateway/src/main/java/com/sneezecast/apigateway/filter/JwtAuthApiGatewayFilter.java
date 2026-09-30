package com.sneezecast.apigateway.filter;

import com.sneezecast.apigateway.filter.JwtAuthApiGatewayFilter.Config;
import com.sneezecast.apigateway.jwt.AccessTokenBlacklistChecker;
import com.sneezecast.apigateway.jwt.JwtVerifier;
import com.sneezecast.apigateway.jwt.exception.JwtErrorCode;
import com.sneezecast.apigateway.jwt.exception.JwtException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.security.SignatureException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * {@code Authorization} 이 있으면 서명 · 만료 · 폐기(블랙리스트)를 확인하고, 없으면 그대로 통과시킨다 — 공개 API 가 있고
 * 인가 판정은 서비스가 한다.
 *
 * <p><b>{@code Authorization} 이 있는데 Bearer 토큰으로 읽히지 않으면 거부한다.</b> "토큰 없음" 으로 통과시키면 헤더가
 * 검증 없이 업스트림에 간다. 서비스의 Resource Server 는 scheme 대소문자를 무시하고 토큰을 읽으므로, 여기서
 * {@code bearer <폐기 토큰>} 을 놓치면 블랙리스트가 우회된다. 그래서 scheme 판정도 대소문자를 무시한다.
 *
 * <p>클라이언트가 보낸 회원 헤더는 항상 지운다. 서비스는 회원을 JWT claim 으로만 식별하지만, 위조 헤더가 업스트림까지 가면
 * 언젠가 그것을 믿는 코드가 생긴다.
 */
@Component
@RequiredArgsConstructor
public class JwtAuthApiGatewayFilter extends AbstractGatewayFilterFactory<Config> {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String MEMBER_ID_HEADER = "X-Authenticated-Member-Id";
    private static final String LEGACY_MEMBER_ID_HEADER = "X-Member-Id";

    private final JwtVerifier jwtVerifier;
    private final AccessTokenBlacklistChecker accessTokenBlacklistChecker;

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {
            ServerHttpRequest sanitizedRequest = sanitizeHeaders(exchange.getRequest());
            List<String> authorizations = sanitizedRequest.getHeaders().getOrEmpty(HttpHeaders.AUTHORIZATION);

            if (authorizations.isEmpty()) {
                return chain.filter(exchange.mutate().request(sanitizedRequest).build());
            }

            return Mono.defer(() -> {
                Claims claims = verify(extractBearerToken(authorizations));
                String tokenId = claims.getId();
                if (!StringUtils.hasText(tokenId)) {
                    // security-core 발급 토큰에는 항상 jti 가 있다. 없으면 폐기 여부를 확인할 수 없어 받지 않는다.
                    throw new JwtException(JwtErrorCode.TOKEN_INVALID);
                }

                // RedisTemplate 은 블로킹이다. Netty 이벤트 루프를 묶지 않게 조회만 boundedElastic 으로 뗀다.
                return Mono.fromCallable(() -> accessTokenBlacklistChecker.isBlacklisted(tokenId))
                    .subscribeOn(Schedulers.boundedElastic())
                    .flatMap(revoked -> {
                        if (revoked) {
                            return Mono.error(new JwtException(JwtErrorCode.TOKEN_REVOKED));
                        }
                        ServerHttpRequest authenticatedRequest = addMemberIdHeader(sanitizedRequest, claims);
                        return chain.filter(exchange.mutate().request(authenticatedRequest).build());
                    });
            });
        };
    }

    /** 헤더가 하나이고 {@code Bearer <token>} (scheme 대소문자 무시) 일 때만 토큰을 꺼낸다. 그 밖은 형식 오류다. */
    private String extractBearerToken(List<String> authorizations) {
        if (authorizations.size() != 1) {
            throw new JwtException(JwtErrorCode.TOKEN_MALFORMED);
        }
        String authorization = authorizations.get(0);
        if (authorization == null || !authorization.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            throw new JwtException(JwtErrorCode.TOKEN_MALFORMED);
        }
        String token = authorization.substring(BEARER_PREFIX.length());
        if (!StringUtils.hasText(token)) {
            throw new JwtException(JwtErrorCode.TOKEN_MALFORMED);
        }
        return token;
    }

    private Claims verify(String jwt) {
        try {
            return jwtVerifier.validateAndGetClaims(jwt);
        } catch (ExpiredJwtException e) {
            throw new JwtException(JwtErrorCode.TOKEN_EXPIRED);
        } catch (SignatureException e) {
            throw new JwtException(JwtErrorCode.TOKEN_SIGNATURE_INVALID);
        } catch (MalformedJwtException e) {
            throw new JwtException(JwtErrorCode.TOKEN_MALFORMED);
        } catch (io.jsonwebtoken.JwtException | IllegalArgumentException e) {
            // 나머지 jjwt 예외(서명 없는 alg=none 토큰의 UnsupportedJwt 등)와 빈 토큰. 하나라도 새면 500 으로 나간다.
            throw new JwtException(JwtErrorCode.TOKEN_INVALID);
        }
    }

    private ServerHttpRequest sanitizeHeaders(ServerHttpRequest request) {
        return request.mutate()
            .headers(headers -> {
                headers.remove(MEMBER_ID_HEADER);
                headers.remove(LEGACY_MEMBER_ID_HEADER);
            })
            .build();
    }

    private ServerHttpRequest addMemberIdHeader(ServerHttpRequest request, Claims claims) {
        String memberId = claims.getSubject();
        if (!StringUtils.hasText(memberId)) {
            return request;
        }
        return request.mutate()
            .header(MEMBER_ID_HEADER, memberId)
            .build();
    }

    public static class Config {

    }
}
