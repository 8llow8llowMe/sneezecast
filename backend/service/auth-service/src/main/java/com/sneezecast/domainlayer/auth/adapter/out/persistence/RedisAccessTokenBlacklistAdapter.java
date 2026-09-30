package com.sneezecast.domainlayer.auth.adapter.out.persistence;

import com.sneezecast.domainlayer.auth.application.port.out.AccessTokenBlacklistPort;
import com.sneezecast.redis.properties.RedisProperties;
import com.sneezecast.security.auth.blacklist.AccessTokenBlacklistVerifier;
import com.sneezecast.security.common.exception.SecurityErrorCode;
import com.sneezecast.security.common.exception.SecurityJwtException;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

/**
 * access token 블랙리스트. auth-service 가 쓰고, API Gateway({@code AccessTokenBlacklistChecker})와 이 서비스의
 * {@code JwtAuthFilter} 가 읽는다.
 *
 * <p><b>키는 게이트웨이와 한 글자도 달라서는 안 된다</b> — {@code {prefix}:auth:accessTokenBlacklist:{jti}}. 어긋나면 로그아웃한
 * 토큰이 게이트웨이를 그대로 통과한다. prefix 는 두 앱 모두 {@link RedisProperties#normalizedKeyPrefix()} 로 읽고, 값은 같은
 * {@code REDIS_KEY_PREFIX} env 로 받는다. 게이트웨이는 존재 여부({@code hasKey})만 보므로 값의 내용과 직렬화 방식은 상관없다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisAccessTokenBlacklistAdapter implements AccessTokenBlacklistVerifier, AccessTokenBlacklistPort {

    private static final String KEY_SEGMENT = ":auth:accessTokenBlacklist:";
    private static final String REVOKED_VALUE = "revoked";

    private final StringRedisTemplate stringRedisTemplate;
    private final RedisProperties redisProperties;

    /**
     * Redis 를 못 읽으면 fail-closed 로 {@code TOKEN_VERIFICATION_UNAVAILABLE}(503)을 던진다. 게이트웨이의 기본 정책과 같다.
     * 연결 실패뿐 아니라 명령 타임아웃 · 서버 오류도 같은 길을 타도록 {@link DataAccessException} 전체를 받는다 — 하나라도 새면
     * 필터가 잡지 못해 봉투 없는 500 이 나간다.
     */
    @Override
    public boolean isRevoked(String tokenId) {
        try {
            return Boolean.TRUE.equals(stringRedisTemplate.hasKey(buildKey(tokenId)));
        } catch (DataAccessException e) {
            // jti 는 남기지 않는다. 장애 종류만 알면 된다.
            log.error("access token blacklist lookup failed reason={}", e.getClass().getSimpleName());
            throw new SecurityJwtException(SecurityErrorCode.TOKEN_VERIFICATION_UNAVAILABLE, e);
        }
    }

    /**
     * 저장 실패는 삼키지 않고 그대로 올린다. 삼키면 로그아웃은 성공으로 보이는데 토큰은 만료까지 계속 통한다.
     */
    @Override
    public void revoke(String tokenId, Duration remainingValidity) {
        Assert.hasText(tokenId, "tokenId must not be blank");
        if (remainingValidity == null || remainingValidity.isZero() || remainingValidity.isNegative()) {
            return;
        }
        stringRedisTemplate.opsForValue().set(buildKey(tokenId), REVOKED_VALUE, remainingValidity);
    }

    private String buildKey(String tokenId) {
        return redisProperties.normalizedKeyPrefix() + KEY_SEGMENT + tokenId;
    }
}
