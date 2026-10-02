package com.sneezecast.domainlayer.member.application.port.out;

import java.time.Duration;

/**
 * 비밀번호 변경 때 현재 비밀번호 확인 실패 횟수(회원 단위) 저장소. TTL 로 사라진다.
 *
 * <p>카운터는 <b>비밀번호 비교(BCrypt) 전에 먼저 올린다</b> — 로그인 시도 제한({@code LoginAttemptStorePort})과 같은 이유다. 읽고 나중에 올리면
 * 동시에 들어온 요청이 모두 읽기를 통과해 요청 수만큼 BCrypt 를 거친다.
 *
 * <p><b>저장소 장애에는 fail-open 이다</b> — 증가는 0 을, 쓰기는 조용히 실패를 돌려준다. 이 카운터는 비밀번호 검증을 대신하지 않는 보조 방어다(이미
 * 로그인한 주체의 요청이기도 하다). 구현은 장애를 ERROR 로그로 남긴다.
 */
public interface MemberPasswordAttemptPort {

    /** 확인 시도 횟수를 1 올리고 누적값을 돌려준다. 성공하면 {@link #clearFailures} 로 지우므로 남는 값은 실패 수다. 수명은 첫 증가부터 {@code ttl}. 장애면 0. */
    long increaseFailureCount(long memberId, Duration ttl);

    /**
     * 상한에 닿았다 — 카운터 수명을 {@code lockDuration} 으로 다시 건다. 카운터가 상한을 넘은 동안은 비교 없이 막히므로 이것이 잠금이다. 카운터를
     * 지우지 않는다(지우면 잠금 직전에 통과한 동시 요청이 0 부터 다시 센다).
     */
    void lock(long memberId, Duration lockDuration);

    /** 현재 비밀번호를 맞혔다 — 카운터를 지운다. */
    void clearFailures(long memberId);
}
