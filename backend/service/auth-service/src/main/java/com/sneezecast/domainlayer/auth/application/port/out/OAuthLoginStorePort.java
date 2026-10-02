package com.sneezecast.domainlayer.auth.application.port.out;

import com.sneezecast.domainlayer.auth.application.model.OAuthLinkTicket;
import com.sneezecast.domainlayer.auth.application.model.OAuthSignupTicket;
import com.sneezecast.domainlayer.member.domain.enums.OAuthProvider;
import java.time.Duration;
import java.util.Optional;

/**
 * 소셜 로그인 흐름의 일회용 값 저장소 — 인가 state, 가입표, 연결 확인표. 모두 TTL 로 사라지고, 꺼낼 때는 <b>원자적으로 꺼내고 지운다</b>(1회성).
 *
 * <p>가입표 · 연결 확인표는 <b>원문을 받지 않는다</b> — 호출자가 SHA-256 해시(hex)로 바꿔 넘긴다(재설정 토큰과 같은 규칙). 저장소가 유출돼도 살아 있는 표를
 * 쓸 수 없게 한다. state 는 인가 주소에 실려 브라우저 · 제공자를 오가는 값이라 그대로 키로 쓴다.
 *
 * <p>저장소 장애는 {@code AuthException(EMAIL_VERIFICATION_UNAVAILABLE)}(503) 으로 올린다.
 */
public interface OAuthLoginStorePort {

    void saveState(String state, OAuthProvider provider, Duration ttl);

    /** @return state 를 발급한 제공자. 없음 · 만료 · 이미 씀이면 empty */
    Optional<OAuthProvider> consumeState(String state);

    void saveSignupTicket(String ticketHash, OAuthSignupTicket ticket, Duration ttl);

    /** @return 없음 · 만료 · 이미 씀이면 empty */
    Optional<OAuthSignupTicket> consumeSignupTicket(String ticketHash);

    void saveLinkTicket(String ticketHash, OAuthLinkTicket ticket, Duration ttl);

    /** @return 없음 · 만료 · 이미 씀이면 empty */
    Optional<OAuthLinkTicket> consumeLinkTicket(String ticketHash);

    /**
     * 인가 주소(state) 발급의 IP 시도 횟수를 1 올리고 누적값을 돌려준다. 윈도우는 첫 증가부터 {@code window} 동안이다(고정 윈도우). 장애면 0 (fail-open —
     * 다른 IP 카운터와 같다).
     */
    long increaseAuthorizeIpCount(String clientIp, Duration window);
}
