package com.sneezecast.domainlayer.auth.application.port.out;

import java.time.Duration;
import java.util.Optional;

/**
 * 비밀번호 재설정 토큰과 재설정 요청의 IP 시도 횟수 저장소. 모두 TTL 로 사라진다.
 *
 * <p><b>토큰 원문을 받지 않는다</b> — 호출자가 SHA-256 해시(hex)로 바꿔 넘긴다. 저장소가 유출돼도 살아 있는 토큰을 쓸 수 없게 한다.
 *
 * <p>저장소 장애는 {@code AuthException(EMAIL_VERIFICATION_UNAVAILABLE)} 로 올린다. IP 시도 횟수만 fail-open 이다.
 */
public interface PasswordResetTokenStorePort {

    /** 토큰 해시 → 정규화한 이메일을 {@code ttl} 동안 저장한다. */
    void saveToken(String tokenHash, String email, Duration ttl);

    /**
     * 토큰을 <b>원자적으로</b> 꺼내고 지운다(1회성). 같은 토큰으로 동시에 들어온 요청 중 하나만 이메일을 받는다.
     *
     * @return 토큰에 묶인 이메일. 없음 · 만료 · 이미 씀이면 empty
     */
    Optional<String> consumeToken(String tokenHash);

    /** 재설정 요청의 IP 시도 횟수를 1 올리고 누적값을 돌려준다. 윈도우는 첫 증가부터 {@code window} 동안이다(고정 윈도우). 장애면 0 (fail-open). */
    long increaseIpResetCount(String clientIp, Duration window);
}
