package com.sneezecast.domainlayer.auth.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sneezecast.domainlayer.auth.application.exception.AuthErrorCode;
import com.sneezecast.domainlayer.auth.application.exception.AuthException;
import com.sneezecast.domainlayer.auth.application.model.OAuthLinkTicket;
import com.sneezecast.domainlayer.auth.application.model.OAuthSignupTicket;
import com.sneezecast.domainlayer.member.domain.enums.OAuthProvider;
import com.sneezecast.redis.properties.RedisProperties;
import com.sneezecast.redis.properties.enums.RedisMode;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * 키 규칙 · 저장 형식 · 장애 처리만 본다. GET+DEL 이 실제로 원자적인지는 {@code RedisRefreshSessionStoreAdapterIntegrationTest} 가 실제 Redis 로 본다.
 */
class RedisOAuthLoginStoreAdapterTest {

    private static final String HASH = "a".repeat(64);

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
    private RedisOAuthLoginStoreAdapter adapter;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        RedisProperties properties = new RedisProperties(RedisMode.SENTINEL, null, null, "mymaster", null, null, "localhost:26379", "sneezecast", null);
        adapter = new RedisOAuthLoginStoreAdapter(redisTemplate, properties);
    }

    @Test
    @DisplayName("state 는 {prefix}:auth:oauthState:{state} 에 제공자 이름을 state 수명으로 두고, 소비는 같은 키로 GET+DEL 스크립트를 부른다")
    void stateKeyAndValue() {
        when(redisTemplate.execute(eq(RedisScripts.GET_AND_DELETE), eq(List.of("sneezecast:auth:oauthState:s1")))).thenReturn("KAKAO");

        adapter.saveState("s1", OAuthProvider.KAKAO, Duration.ofMinutes(10));

        verify(valueOperations).set("sneezecast:auth:oauthState:s1", "KAKAO", Duration.ofMinutes(10));
        assertThat(adapter.consumeState("s1")).hasValue(OAuthProvider.KAKAO);
        assertThat(adapter.consumeState("other")).isEmpty();
    }

    @Test
    @DisplayName("가입표는 해시 키에 JSON {provider, email, nickname} 으로 두고 그대로 되읽는다")
    void signupTicketRoundTrip() {
        OAuthSignupTicket ticket = new OAuthSignupTicket(OAuthProvider.KAKAO, "user@example.com", "재채기😀탐정");
        String key = "sneezecast:auth:oauthSignupTicket:" + HASH;

        adapter.saveSignupTicket(HASH, ticket, Duration.ofMinutes(30));

        ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(eq(key), value.capture(), eq(Duration.ofMinutes(30)));
        assertThat(value.getValue()).contains("\"provider\":\"KAKAO\"").contains("\"email\":\"user@example.com\"");
        when(redisTemplate.execute(eq(RedisScripts.GET_AND_DELETE), eq(List.of(key)))).thenReturn(value.getValue());
        assertThat(adapter.consumeSignupTicket(HASH)).hasValue(ticket);
    }

    @Test
    @DisplayName("연결 확인표는 해시 키에 JSON {memberId, provider} 로 두고 그대로 되읽는다")
    void linkTicketRoundTrip() {
        OAuthLinkTicket ticket = new OAuthLinkTicket(1843956734582784L, OAuthProvider.KAKAO);
        String key = "sneezecast:auth:oauthLinkTicket:" + HASH;

        adapter.saveLinkTicket(HASH, ticket, Duration.ofMinutes(10));

        ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(eq(key), value.capture(), eq(Duration.ofMinutes(10)));
        when(redisTemplate.execute(eq(RedisScripts.GET_AND_DELETE), eq(List.of(key)))).thenReturn(value.getValue());
        assertThat(adapter.consumeLinkTicket(HASH)).hasValue(ticket);
    }

    @Test
    @DisplayName("저장값이 깨졌으면(형식 변경 등) 없는 값과 같게 empty 다")
    void unreadableValueIsAbsent() {
        when(redisTemplate.execute(eq(RedisScripts.GET_AND_DELETE), anyList())).thenReturn("not-json");

        assertThat(adapter.consumeSignupTicket(HASH)).isEmpty();
        assertThat(adapter.consumeLinkTicket(HASH)).isEmpty();
        assertThat(adapter.consumeState("s1")).isEmpty();
    }

    @Test
    @DisplayName("인가 IP 카운터는 {prefix}:auth:oauthAuthorizeIp:{ip} 를 올리고, 첫 증가에 창 길이 TTL 을 건다")
    void authorizeIpCounterKeyAndTtl() {
        String key = "sneezecast:auth:oauthAuthorizeIp:203.0.113.10";
        when(valueOperations.increment(key)).thenReturn(1L, 2L);
        when(redisTemplate.getExpire(key)).thenReturn(500L);

        assertThat(adapter.increaseAuthorizeIpCount("203.0.113.10", Duration.ofMinutes(10))).isEqualTo(1L);
        assertThat(adapter.increaseAuthorizeIpCount("203.0.113.10", Duration.ofMinutes(10))).isEqualTo(2L);

        verify(redisTemplate, times(1)).expire(key, Duration.ofMinutes(10));
    }

    @Test
    @DisplayName("인가 IP 카운터에 TTL 이 빠져 있으면(첫 증가 직후 장애) 다음 증가에서 다시 건다")
    void authorizeIpCounterRestoresMissingTtl() {
        String key = "sneezecast:auth:oauthAuthorizeIp:203.0.113.10";
        when(valueOperations.increment(key)).thenReturn(5L);
        when(redisTemplate.getExpire(key)).thenReturn(-1L);

        adapter.increaseAuthorizeIpCount("203.0.113.10", Duration.ofMinutes(10));

        verify(redisTemplate).expire(key, Duration.ofMinutes(10));
    }

    @Test
    @DisplayName("인가 IP 카운터 장애는 fail-open(0) 이다 — 다른 IP 카운터와 같다")
    void authorizeIpCounterFailsOpen() {
        when(valueOperations.increment(anyString())).thenThrow(new RedisConnectionFailureException("down"));

        assertThat(adapter.increaseAuthorizeIpCount("203.0.113.10", Duration.ofMinutes(10))).isZero();
    }

    @Test
    @DisplayName("저장 · 소비 장애는 503 AUTH_006 이다")
    void storeFailureIsUnavailable() {
        doThrow(new RedisConnectionFailureException("down")).when(valueOperations).set(anyString(), anyString(), any(Duration.class));
        when(redisTemplate.execute(eq(RedisScripts.GET_AND_DELETE), anyList())).thenThrow(new RedisConnectionFailureException("down"));

        assertThatThrownBy(() -> adapter.saveState("s1", OAuthProvider.KAKAO, Duration.ofMinutes(10)))
            .isInstanceOfSatisfying(AuthException.class, e -> assertThat(e.getErrorCode()).isEqualTo(AuthErrorCode.EMAIL_VERIFICATION_UNAVAILABLE));
        assertThatThrownBy(() -> adapter.consumeLinkTicket(HASH))
            .isInstanceOfSatisfying(AuthException.class, e -> assertThat(e.getErrorCode()).isEqualTo(AuthErrorCode.EMAIL_VERIFICATION_UNAVAILABLE));
    }
}
