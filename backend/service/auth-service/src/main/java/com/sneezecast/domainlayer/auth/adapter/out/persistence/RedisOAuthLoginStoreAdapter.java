package com.sneezecast.domainlayer.auth.adapter.out.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.sneezecast.domainlayer.auth.application.exception.AuthErrorCode;
import com.sneezecast.domainlayer.auth.application.exception.AuthException;
import com.sneezecast.domainlayer.auth.application.model.OAuthLinkTicket;
import com.sneezecast.domainlayer.auth.application.model.OAuthSignupTicket;
import com.sneezecast.domainlayer.auth.application.port.out.OAuthLoginStorePort;
import com.sneezecast.domainlayer.member.domain.enums.OAuthProvider;
import com.sneezecast.redis.properties.RedisProperties;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 카카오 로그인의 일회용 값을 Redis(TTL)에 둔다. 키는 {@code {prefix}:auth:{종류}:{값}} 규칙이다.
 *
 * <ul>
 *   <li>{@code oauthState:{state}} — 제공자 이름, 수명 = {@code auth.oauth.state-ttl}</li>
 *   <li>{@code oauthSignupTicket:{표 SHA-256 hex}} — JSON {@code {provider, email, nickname}}, 수명 = {@code auth.oauth.signup-ticket-ttl}</li>
 *   <li>{@code oauthLinkTicket:{표 SHA-256 hex}} — JSON {@code {memberId, provider}}, 수명 = {@code auth.oauth.link-ticket-ttl}</li>
 *   <li>{@code oauthAuthorizeIp:{ip}} — 인가 주소 발급 IP 시도 횟수, 수명 = {@code auth.oauth.authorize-ip-window} (첫 증가부터, 고정 윈도우)</li>
 * </ul>
 *
 * <p>꺼낼 때는 GET+DEL 을 한 Lua 로 원자 처리한다({@link RedisScripts#GET_AND_DELETE}) — 같은 값으로 동시에 들어온 요청 중 하나만 받는다. 표 원문은 저장하지
 * 않는다. 저장소 장애는 {@link AuthErrorCode#EMAIL_VERIFICATION_UNAVAILABLE}(503)로 바꾸고, IP 카운터만 fail-open 이다. 값 · 이메일 · IP 는 로그에 남기지
 * 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisOAuthLoginStoreAdapter implements OAuthLoginStorePort {

    static final String STATE = "oauthState";
    static final String SIGNUP_TICKET = "oauthSignupTicket";
    static final String LINK_TICKET = "oauthLinkTicket";
    static final String AUTHORIZE_IP = "oauthAuthorizeIp";

    /** 저장값 직렬화 전용. 앱 ObjectMapper 설정이 바뀌어도 저장 형식이 흔들리지 않게 따로 둔다. */
    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

    private final StringRedisTemplate stringRedisTemplate;
    private final RedisProperties redisProperties;

    @Override
    public void saveState(String state, OAuthProvider provider, Duration ttl) {
        save(STATE, state, provider.name(), ttl);
    }

    @Override
    public Optional<OAuthProvider> consumeState(String state) {
        return consume(STATE, state).flatMap(value -> parse(STATE, () -> OAuthProvider.valueOf(value)));
    }

    @Override
    public void saveSignupTicket(String ticketHash, OAuthSignupTicket ticket, Duration ttl) {
        save(SIGNUP_TICKET, ticketHash, write(new SignupTicketValue(ticket.provider(), ticket.email(), ticket.nickname())), ttl);
    }

    @Override
    public Optional<OAuthSignupTicket> consumeSignupTicket(String ticketHash) {
        return consume(SIGNUP_TICKET, ticketHash).flatMap(value -> parse(SIGNUP_TICKET, () -> {
            SignupTicketValue saved = OBJECT_MAPPER.readValue(value, SignupTicketValue.class);
            return new OAuthSignupTicket(saved.provider(), saved.email(), saved.nickname());
        }));
    }

    @Override
    public void saveLinkTicket(String ticketHash, OAuthLinkTicket ticket, Duration ttl) {
        save(LINK_TICKET, ticketHash, write(new LinkTicketValue(ticket.memberId(), ticket.provider())), ttl);
    }

    @Override
    public Optional<OAuthLinkTicket> consumeLinkTicket(String ticketHash) {
        return consume(LINK_TICKET, ticketHash).flatMap(value -> parse(LINK_TICKET, () -> {
            LinkTicketValue saved = OBJECT_MAPPER.readValue(value, LinkTicketValue.class);
            return new OAuthLinkTicket(saved.memberId(), saved.provider());
        }));
    }

    /**
     * INCR 와 EXPIRE 는 원자적이지 않다 — 첫 증가 직후 장애가 나면 TTL 없는 카운터가 영구히 남는다. 첫 증가가 아니어도 TTL 이 없으면 다시 걸어 스스로
     * 회복한다({@link RedisPasswordResetTokenStoreAdapter} 의 IP 카운터와 같은 규칙). 장애에는 fail-open 이다.
     */
    @Override
    public long increaseAuthorizeIpCount(String clientIp, Duration window) {
        try {
            String key = buildKey(AUTHORIZE_IP, clientIp);
            Long count = stringRedisTemplate.opsForValue().increment(key);
            if (count != null && count == 1L) {
                stringRedisTemplate.expire(key, window);
            } else {
                Long remaining = stringRedisTemplate.getExpire(key);
                if (remaining != null && remaining < 0) {
                    stringRedisTemplate.expire(key, window);
                }
            }
            return count == null ? 0L : count;
        } catch (DataAccessException exception) {
            log.error("oauth authorize ip counter failed, fail-open reason={}", exception.getClass().getSimpleName());
            return 0L;
        }
    }

    private void save(String type, String key, String value, Duration ttl) {
        execute(type, () -> {
            stringRedisTemplate.opsForValue().set(buildKey(type, key), value, ttl);
            return null;
        });
    }

    private Optional<String> consume(String type, String key) {
        return execute(type, () -> Optional.ofNullable(stringRedisTemplate.execute(RedisScripts.GET_AND_DELETE, List.of(buildKey(type, key)))));
    }

    private <T> T execute(String type, Supplier<T> operation) {
        try {
            return operation.get();
        } catch (DataAccessException exception) {
            log.error("oauth login store failed type={} reason={}", type, exception.getClass().getSimpleName());
            throw new AuthException(AuthErrorCode.EMAIL_VERIFICATION_UNAVAILABLE, exception);
        }
    }

    /** 저장값이 깨졌으면(배포 사이 형식 변경 등) 없는 값과 같게 본다 — 화면은 처음부터 다시 한다. 값은 로그에 남기지 않는다. */
    private static <T> Optional<T> parse(String type, ValueReader<T> reader) {
        try {
            return Optional.of(reader.read());
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            log.warn("oauth login store value unreadable, treated as absent type={} reason={}", type, exception.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private static String write(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            // 문자열 · enum · 숫자로만 된 record 라 직렬화가 실패하지 않는다. 실패하면 코드가 깨진 것이다.
            throw new IllegalStateException("oauth login store value serialization failed", exception);
        }
    }

    private String buildKey(String type, String value) {
        return redisProperties.normalizedKeyPrefix() + ":auth:" + type + ":" + value;
    }

    @FunctionalInterface
    private interface ValueReader<T> {

        T read() throws JsonProcessingException;
    }

    record SignupTicketValue(OAuthProvider provider, String email, String nickname) {

    }

    record LinkTicketValue(long memberId, OAuthProvider provider) {

    }
}
