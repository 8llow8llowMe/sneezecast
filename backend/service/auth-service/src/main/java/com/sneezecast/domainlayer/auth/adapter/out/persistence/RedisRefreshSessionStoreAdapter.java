package com.sneezecast.domainlayer.auth.adapter.out.persistence;

import com.sneezecast.domainlayer.auth.application.exception.AuthErrorCode;
import com.sneezecast.domainlayer.auth.application.exception.AuthException;
import com.sneezecast.domainlayer.auth.application.model.NewRefreshSession;
import com.sneezecast.domainlayer.auth.application.model.RefreshRotation;
import com.sneezecast.domainlayer.auth.application.model.RefreshRotationResult;
import com.sneezecast.domainlayer.auth.application.model.RefreshRotationResult.Outcome;
import com.sneezecast.domainlayer.auth.application.model.SessionAccessToken;
import com.sneezecast.domainlayer.auth.application.port.out.RefreshSessionStorePort;
import com.sneezecast.domainlayer.auth.application.port.out.query.RefreshSessionQueryResult;
import com.sneezecast.redis.properties.RedisProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * 로그인 세션(기기)별 refresh 상태를 Redis(TTL)에 둔다.
 *
 * <ul>
 *   <li>{@code {prefix}:auth:refreshSession:{memberId}:{sessionId}} HASH — {@code tokenId}(현재 refresh jti) · {@code previousTokenId} ·
 *       {@code rotatedAt} · {@code deviceLabel} · {@code createdAt} · {@code lastUsedAt} · {@code accessTokenId} · {@code accessExpiresAt}
 *       (시각은 epoch ms). <b>refresh 토큰 원문은 저장하지 않는다</b> — jti 만 비교하므로 저장소가 새도 토큰을 재구성할 수 없다.</li>
 *   <li>{@code {prefix}:auth:refreshSessions:{memberId}} ZSET — member = sessionId, score = lastUsedAt. 기기 수 상한과 목록 순서의 인덱스다.</li>
 * </ul>
 * 둘 다 TTL = refresh 만료이고 로그인 · 회전 때마다 다시 건다(sliding). 해시가 TTL 로 먼저 사라져 인덱스에 sessionId 만 남을 수 있는데, 판정은 늘
 * 해시 기준이고 남은 항목은 목록 조회 · 밀어내기 · 전체 삭제 때 정리된다.
 *
 * <p>여러 명령이 엮이는 연산은 Lua 하나로 원자 처리한다 — 회전에서 "비교 → 갱신" 사이에 다른 재발급이 끼면 두 요청이 모두 이겨 세션이 갈라진다.
 * 인덱스에 든 다른 세션의 해시 키는 스크립트가 {@code ARGV} 의 키 접두어로 만든다. 단일 샤드(Sentinel) 전제이고, Redis Cluster 로 옮기면
 * 회원 단위 해시태그로 키를 한 슬롯에 묶어야 한다.
 *
 * <p>장애는 삼키지 않는다 — 세션 저장이 실패했는데 로그인이 성공한 것처럼 보이면 첫 재발급에서 원인 모를 강제 로그아웃이 된다. 봉투 없는
 * 500 대신 {@link AuthErrorCode#SESSION_STORE_UNAVAILABLE}(503)로 바꾼다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisRefreshSessionStoreAdapter implements RefreshSessionStorePort {

    /**
     * 새 세션 저장 + 기기 수 상한 밀어내기. KEYS[1]=세션 해시, KEYS[2]=세션 인덱스. ARGV: 1 sessionId · 2 refresh jti · 3 기기 이름 · 4 지금(ms)
     * · 5 access jti · 6 access 만료(ms) · 7 TTL(ms) · 8 최대 기기 수 · 9 세션 해시 키 접두어. 마지막 사용이 오래된 순으로 밀어내되 방금 만든
     * 세션은 건너뛴다(같은 ms 의 score 가 겹쳐도 새 세션이 밀리지 않게). 밀어낸 세션들의 {access jti, access 만료} 쌍을 이어 붙여 돌려준다
     * ({@link #DELETE_ALL_EXCEPT_SCRIPT} 와 같은 모양).
     */
    @SuppressWarnings("rawtypes")
    static final RedisScript<List> SAVE_SCRIPT = new DefaultRedisScript<>("""
        redis.call('DEL', KEYS[1])
        redis.call('HSET', KEYS[1], 'tokenId', ARGV[2], 'deviceLabel', ARGV[3], 'createdAt', ARGV[4], 'lastUsedAt', ARGV[4],
          'accessTokenId', ARGV[5], 'accessExpiresAt', ARGV[6])
        redis.call('PEXPIRE', KEYS[1], ARGV[7])
        redis.call('ZADD', KEYS[2], ARGV[4], ARGV[1])
        redis.call('PEXPIRE', KEYS[2], ARGV[7])
        local overflow = redis.call('ZCARD', KEYS[2]) - tonumber(ARGV[8])
        local evicted = 0
        local result = {}
        if overflow > 0 then
          for _, victim in ipairs(redis.call('ZRANGE', KEYS[2], 0, -1)) do
            if evicted >= overflow then break end
            if victim ~= ARGV[1] then
              local access = redis.call('HMGET', ARGV[9] .. victim, 'accessTokenId', 'accessExpiresAt')
              redis.call('DEL', ARGV[9] .. victim)
              redis.call('ZREM', KEYS[2], victim)
              table.insert(result, access[1] or '')
              table.insert(result, access[2] or '')
              evicted = evicted + 1
            end
          end
        end
        return result
        """, List.class);

    /**
     * refresh 회전. KEYS[1]=세션 해시, KEYS[2]=세션 인덱스. ARGV: 1 제시 jti · 2 새 jti · 3 지금(ms) · 4 유예(ms) · 5 TTL(ms) · 6 새 access jti ·
     * 7 새 access 만료(ms) · 8 sessionId. 돌려주는 첫 값이 결과 코드다.
     * <ul>
     *   <li>0 — 해시 없음 (만료 · 밀려남 · 폐기)</li>
     *   <li>1 — 현재 jti 와 같음: 회전하고 TTL · 인덱스 score 를 갱신</li>
     *   <li>2 — 직전 jti 이고 회전 뒤 유예 안: 동시 재발급 경합. 세션을 건드리지 않는다</li>
     *   <li>3 — 그 밖(이미 회전된 jti 의 재사용): 세션을 폐기하고 그 세션의 마지막 access jti · 만료를 함께 돌려준다</li>
     * </ul>
     */
    @SuppressWarnings("rawtypes")
    static final RedisScript<List> ROTATE_SCRIPT = new DefaultRedisScript<>("""
        if redis.call('EXISTS', KEYS[1]) == 0 then
          return {'0'}
        end
        if redis.call('HGET', KEYS[1], 'tokenId') == ARGV[1] then
          redis.call('HSET', KEYS[1], 'tokenId', ARGV[2], 'previousTokenId', ARGV[1], 'rotatedAt', ARGV[3], 'lastUsedAt', ARGV[3],
            'accessTokenId', ARGV[6], 'accessExpiresAt', ARGV[7])
          redis.call('PEXPIRE', KEYS[1], ARGV[5])
          redis.call('ZADD', KEYS[2], ARGV[3], ARGV[8])
          redis.call('PEXPIRE', KEYS[2], ARGV[5])
          return {'1'}
        end
        local previous = redis.call('HGET', KEYS[1], 'previousTokenId')
        local rotatedAt = tonumber(redis.call('HGET', KEYS[1], 'rotatedAt') or '0')
        if previous == ARGV[1] and tonumber(ARGV[3]) - rotatedAt <= tonumber(ARGV[4]) then
          return {'2'}
        end
        local access = redis.call('HMGET', KEYS[1], 'accessTokenId', 'accessExpiresAt')
        redis.call('DEL', KEYS[1])
        redis.call('ZREM', KEYS[2], ARGV[8])
        return {'3', access[1] or '', access[2] or ''}
        """, List.class);

    /** 세션 하나 삭제. KEYS[1]=세션 해시, KEYS[2]=세션 인덱스, ARGV[1]=sessionId. 지운 세션의 {access jti, access 만료} 를 돌려준다(없었으면 빈 값). */
    @SuppressWarnings("rawtypes")
    static final RedisScript<List> DELETE_SCRIPT = new DefaultRedisScript<>("""
        local access = redis.call('HMGET', KEYS[1], 'accessTokenId', 'accessExpiresAt')
        redis.call('DEL', KEYS[1])
        redis.call('ZREM', KEYS[2], ARGV[1])
        return {access[1] or '', access[2] or ''}
        """, List.class);

    /**
     * 남길 세션 하나를 뺀 전체 삭제. KEYS[1]=세션 인덱스, ARGV[1]=세션 해시 키 접두어, ARGV[2]=남길 sessionId(없으면 빈 문자열).
     * 지운 세션들의 {access jti, access 만료} 쌍을 이어 붙여 돌려준다.
     */
    @SuppressWarnings("rawtypes")
    static final RedisScript<List> DELETE_ALL_EXCEPT_SCRIPT = new DefaultRedisScript<>("""
        local result = {}
        for _, id in ipairs(redis.call('ZRANGE', KEYS[1], 0, -1)) do
          if id ~= ARGV[2] then
            local access = redis.call('HMGET', ARGV[1] .. id, 'accessTokenId', 'accessExpiresAt')
            redis.call('DEL', ARGV[1] .. id)
            redis.call('ZREM', KEYS[1], id)
            table.insert(result, access[1] or '')
            table.insert(result, access[2] or '')
          end
        end
        return result
        """, List.class);

    /**
     * 목록. KEYS[1]=세션 인덱스, ARGV[1]=세션 해시 키 접두어. 마지막 사용 내림차순으로 {sessionId, 기기 이름, createdAt, lastUsedAt} 넷씩 이어 붙여
     * 돌려준다. 해시가 사라진(만료) 항목은 빼고 인덱스에서도 지운다.
     */
    @SuppressWarnings("rawtypes")
    static final RedisScript<List> LIST_SCRIPT = new DefaultRedisScript<>("""
        local result = {}
        for _, id in ipairs(redis.call('ZREVRANGE', KEYS[1], 0, -1)) do
          local fields = redis.call('HMGET', ARGV[1] .. id, 'deviceLabel', 'createdAt', 'lastUsedAt')
          if fields[2] then
            table.insert(result, id)
            table.insert(result, fields[1] or '')
            table.insert(result, fields[2])
            table.insert(result, fields[3] or fields[2])
          else
            redis.call('ZREM', KEYS[1], id)
          end
        end
        return result
        """, List.class);

    private final StringRedisTemplate stringRedisTemplate;
    private final RedisProperties redisProperties;

    @Override
    public List<SessionAccessToken> save(NewRefreshSession session, Duration ttl, int maxDevices) {
        List<String> result = executeForStrings(() -> stringRedisTemplate.execute(SAVE_SCRIPT,
            List.of(sessionKey(session.memberId(), session.sessionId()), indexKey(session.memberId())),
            session.sessionId(), session.refreshTokenId(), session.deviceLabel(), millis(session.issuedAt()),
            session.accessToken().tokenId(), millis(session.accessToken().expiresAt()), String.valueOf(ttl.toMillis()),
            String.valueOf(maxDevices), sessionKeyPrefix(session.memberId())));
        if (!result.isEmpty()) {
            log.info("refresh session evicted over device limit memberId={} evicted={}", session.memberId(), result.size() / 2);
        }
        return toAccessTokens(result);
    }

    @Override
    public RefreshRotationResult rotate(RefreshRotation rotation, Duration rotationGrace, Duration ttl) {
        List<String> result = executeForStrings(() -> stringRedisTemplate.execute(ROTATE_SCRIPT,
            List.of(sessionKey(rotation.memberId(), rotation.sessionId()), indexKey(rotation.memberId())),
            rotation.presentedTokenId(), rotation.newTokenId(), millis(rotation.rotatedAt()), String.valueOf(rotationGrace.toMillis()),
            String.valueOf(ttl.toMillis()), rotation.newAccessToken().tokenId(), millis(rotation.newAccessToken().expiresAt()), rotation.sessionId()));

        return switch (result.isEmpty() ? "" : result.getFirst()) {
            case "0" -> RefreshRotationResult.of(Outcome.SESSION_NOT_FOUND);
            case "1" -> RefreshRotationResult.of(Outcome.ROTATED);
            case "2" -> RefreshRotationResult.of(Outcome.CONCURRENT_ROTATION);
            case "3" -> new RefreshRotationResult(Outcome.REUSE_DETECTED, toAccessToken(result, 1));
            default -> throw unexpectedReply("rotate");
        };
    }

    @Override
    public Optional<SessionAccessToken> delete(long memberId, String sessionId) {
        List<String> result = executeForStrings(() -> stringRedisTemplate.execute(DELETE_SCRIPT,
            List.of(sessionKey(memberId, sessionId), indexKey(memberId)), sessionId));
        return Optional.ofNullable(toAccessToken(result, 0));
    }

    @Override
    public List<SessionAccessToken> deleteAllExcept(long memberId, String keepSessionId) {
        List<String> result = executeForStrings(() -> stringRedisTemplate.execute(DELETE_ALL_EXCEPT_SCRIPT,
            List.of(indexKey(memberId)), sessionKeyPrefix(memberId), keepSessionId == null ? "" : keepSessionId));
        return toAccessTokens(result);
    }

    @Override
    public List<RefreshSessionQueryResult> findAll(long memberId) {
        List<String> result = executeForStrings(() -> stringRedisTemplate.execute(LIST_SCRIPT, List.of(indexKey(memberId)), sessionKeyPrefix(memberId)));
        if (result.size() % 4 != 0) {
            throw unexpectedReply("list");
        }
        List<RefreshSessionQueryResult> sessions = new ArrayList<>(result.size() / 4);
        for (int index = 0; index < result.size(); index += 4) {
            sessions.add(RefreshSessionQueryResult.builder()
                .sessionId(result.get(index))
                .deviceLabel(result.get(index + 1))
                .createdAt(Instant.ofEpochMilli(Long.parseLong(result.get(index + 2))))
                .lastUsedAt(Instant.ofEpochMilli(Long.parseLong(result.get(index + 3))))
                .build());
        }
        return List.copyOf(sessions);
    }

    /** {jti, 만료(ms)} 쌍을 이어 붙인 응답을 읽는다. 기록이 없는 쌍은 뺀다. */
    private static List<SessionAccessToken> toAccessTokens(List<String> values) {
        List<SessionAccessToken> accessTokens = new ArrayList<>();
        for (int index = 0; index + 1 < values.size(); index += 2) {
            SessionAccessToken accessToken = toAccessToken(values, index);
            if (accessToken != null) {
                accessTokens.add(accessToken);
            }
        }
        return List.copyOf(accessTokens);
    }

    /** {jti, 만료(ms)} 한 쌍을 읽는다. 둘 중 하나라도 비었으면(기록 없음) null 이다. */
    private static SessionAccessToken toAccessToken(List<String> values, int offset) {
        if (values.size() < offset + 2) {
            return null;
        }
        String tokenId = values.get(offset);
        String expiresAt = values.get(offset + 1);
        if (tokenId == null || tokenId.isEmpty() || expiresAt == null || expiresAt.isEmpty()) {
            return null;
        }
        return new SessionAccessToken(tokenId, Instant.ofEpochMilli(Long.parseLong(expiresAt)));
    }

    @SuppressWarnings("rawtypes")
    private List<String> executeForStrings(Supplier<List> operation) {
        List<?> result = execute(operation);
        if (result == null) {
            return List.of();
        }
        List<String> values = new ArrayList<>(result.size());
        for (Object value : result) {
            values.add(value == null ? null : value.toString());
        }
        return values;
    }

    private <T> T execute(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (DataAccessException exception) {
            // 회원 · 세션 · 토큰 값은 남기지 않는다. 장애 종류만 알면 된다.
            log.error("refresh session store failed reason={}", exception.getClass().getSimpleName());
            throw new AuthException(AuthErrorCode.SESSION_STORE_UNAVAILABLE, exception);
        }
    }

    private static AuthException unexpectedReply(String operation) {
        log.error("refresh session store returned unexpected reply operation={}", operation);
        return new AuthException(AuthErrorCode.SESSION_STORE_UNAVAILABLE);
    }

    private static String millis(Instant instant) {
        return String.valueOf(instant.toEpochMilli());
    }

    private String sessionKey(long memberId, String sessionId) {
        return sessionKeyPrefix(memberId) + sessionId;
    }

    private String sessionKeyPrefix(long memberId) {
        return redisProperties.normalizedKeyPrefix() + ":auth:refreshSession:" + memberId + ":";
    }

    private String indexKey(long memberId) {
        return redisProperties.normalizedKeyPrefix() + ":auth:refreshSessions:" + memberId;
    }
}
