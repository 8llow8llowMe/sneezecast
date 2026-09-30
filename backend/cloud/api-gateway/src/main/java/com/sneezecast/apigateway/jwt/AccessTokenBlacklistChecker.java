package com.sneezecast.apigateway.jwt;

import com.sneezecast.apigateway.jwt.exception.JwtErrorCode;
import com.sneezecast.apigateway.jwt.exception.JwtException;
import com.sneezecast.apigateway.jwt.properties.JwtVerificationProperties;
import com.sneezecast.redis.properties.RedisProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 로그아웃 · 세션 폐기로 무효화된 access token(jti)을 Redis 에서 확인한다. 키는 auth-service 가 쓴다.
 *
 * <p>Redis 를 못 읽으면 {@code jwt.blacklist-fail-open} 에 따라 통과시키거나 503 으로 거부한다.
 * 연결 실패뿐 아니라 명령 타임아웃 · 서버 오류도 같은 정책을 탄다 — {@link DataAccessException} 전체를 받는다.
 * 하나라도 새면 의도한 실패가 500 으로 나가 진짜 장애와 구분되지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AccessTokenBlacklistChecker {

    private final RedisTemplate<String, Object> redisTemplate;
    private final JwtVerificationProperties jwtVerificationProperties;
    private final RedisProperties redisProperties;

    public boolean isBlacklisted(String tokenId) {
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(buildKey(tokenId)));
        } catch (DataAccessException e) {
            log.error("access token blacklist lookup failed failOpen={} reason={}",
                jwtVerificationProperties.blacklistFailOpen(), e.getClass().getSimpleName());
            if (jwtVerificationProperties.blacklistFailOpen()) {
                return false;
            }
            throw new JwtException(JwtErrorCode.TOKEN_VERIFICATION_UNAVAILABLE);
        }
    }

    private String buildKey(String tokenId) {
        return redisProperties.normalizedKeyPrefix() + ":auth:accessTokenBlacklist:" + tokenId;
    }
}
