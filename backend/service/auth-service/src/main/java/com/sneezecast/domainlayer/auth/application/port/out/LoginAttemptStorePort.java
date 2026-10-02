package com.sneezecast.domainlayer.auth.application.port.out;

import java.time.Duration;

/**
 * 로그인 시도 횟수 · 잠금 저장소. 모두 TTL 로 사라진다.
 *
 * <p>카운터는 <b>비밀번호 비교(BCrypt) 전에 먼저 올린다</b> — 읽고 나중에 올리면 같은 이메일 · IP 로 동시에 들어온 요청이 모두 읽기를 통과해
 * 상한만큼이 아니라 요청 수만큼 BCrypt 를 거친다. INCR 의 원자성으로 상한 안의 몇 건만 비교까지 가게 한다.
 *
 * <p><b>저장소 장애에는 fail-open 이다</b> — 조회는 "잠기지 않음 · 0회" 를, 쓰기는 조용히 실패를 돌려준다. 이 저장소는 비밀번호 검증을 대신하지
 * 않는 보조 방어라, 장애 때 막아 버리면 정상 사용자 전원이 로그인하지 못한다. 구현은 장애를 ERROR 로그로 남긴다.
 */
public interface LoginAttemptStorePort {

    /** 이메일이 잠겨 있는지. 장애면 false. */
    boolean isLocked(String email);

    /** 이메일 시도 횟수를 1 올리고 누적값을 돌려준다. 성공하면 {@link #clearFailures} 로 지우므로 남는 값은 실패 수다. 수명은 첫 증가부터 {@code ttl}. 장애면 0. */
    long increaseFailureCount(String email, Duration ttl);

    /**
     * 이메일을 {@code lockDuration} 동안 잠근다. 시도 카운터는 <b>지우지 않는다</b> — 지우면 잠금 직전에 잠금 확인을 통과한 동시 요청이 0 부터 다시 세어
     * BCrypt 까지 간다. 카운터 수명(첫 증가부터 잠금 시간)은 잠금보다 먼저 끝나므로 잠금이 풀리면 0 부터 다시 센다.
     */
    void lock(String email, Duration lockDuration);

    /** 로그인 성공 — 이메일 실패 카운터와 잠금을 지운다. */
    void clearFailures(String email);

    /** IP 시도 횟수를 1 올리고 누적값을 돌려준다. 윈도우는 첫 증가부터 {@code window} 동안이다(고정 윈도우). 장애면 0. */
    long increaseIpAttemptCount(String clientIp, Duration window);

    /**
     * 로그인 성공 — 먼저 올린 IP 시도 몫 하나를 되돌려 IP 카운터가 실패만 세게 한다. 0 아래로 내리지 않고, 키가 없으면(윈도우 만료) 아무것도 하지
     * 않는다. 다른 사용자의 실패까지 지우지 않도록 전체를 지우지 않는다. 장애면 무시한다.
     */
    void decreaseIpAttemptCount(String clientIp);
}
