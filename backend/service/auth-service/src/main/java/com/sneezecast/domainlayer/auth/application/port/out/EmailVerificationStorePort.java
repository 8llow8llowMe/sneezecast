package com.sneezecast.domainlayer.auth.application.port.out;

import com.sneezecast.domainlayer.auth.application.model.EmailCodePurpose;
import java.time.Duration;
import java.util.Optional;

/**
 * 이메일 인증 상태(코드 · 오입력 횟수 · 재발송 쿨다운 · IP 발송 · 검증 횟수 · 가입 인증 완료 표시) 저장소. 모두 TTL 로 사라진다.
 *
 * <p>코드 · 카운터는 {@link EmailCodePurpose} 별로 따로 저장한다 — 가입 코드와 재설정 코드는 서로를 대신하지 못하고, 한쪽의 쿨다운 · 상한이
 * 다른 쪽을 막지 않는다. 인증 완료 표시는 가입에만 있다.
 *
 * <p>저장소 장애는 {@code AuthException(EMAIL_VERIFICATION_UNAVAILABLE)} 로 올린다. 예외는 IP 발송 · 검증 횟수 — 보조 방어라
 * fail-open 이다.
 */
public interface EmailVerificationStorePort {

    void saveCode(EmailCodePurpose purpose, String email, String code, Duration ttl);

    Optional<String> findCode(EmailCodePurpose purpose, String email);

    void deleteCode(EmailCodePurpose purpose, String email);

    /** 가입 인증 완료 표시. 이 안에 가입해야 한다. */
    void saveVerified(String email, Duration ttl);

    boolean isVerified(String email);

    void deleteVerified(String email);

    /**
     * 재발송 쿨다운을 원자적으로 얻는다.
     *
     * @return 새로 얻었으면 true, 이미 쿨다운 중이면 false
     */
    boolean tryAcquireCooldown(EmailCodePurpose purpose, String email, Duration ttl);

    /** 코드 오입력 횟수를 1 올리고 누적값을 돌려준다. 카운터 수명은 코드 TTL 과 같다. */
    long increaseVerifyFailureCount(EmailCodePurpose purpose, String email, Duration ttl);

    void clearVerifyFailures(EmailCodePurpose purpose, String email);

    /**
     * IP 별 현재 윈도우의 발송 횟수를 센다 (증가시키지 않는다). 쿨다운에 막힐 요청까지 세지 않도록 조회와 증가를 나눈다.
     * 저장소 장애면 0 을 돌려준다 (fail-open).
     */
    long findIpSendCount(EmailCodePurpose purpose, String clientIp);

    /**
     * IP 별 발송 횟수를 1 올리고 누적값을 돌려준다. 실제로 발송하는 요청에서만 부른다. 윈도우는 첫 증가 시점부터 {@code window} 동안이다.
     * 저장소 장애면 0 을 돌려준다 (fail-open — 상한은 보조 방어라 발송 자체를 막지 않는다).
     */
    long increaseIpSendCount(EmailCodePurpose purpose, String clientIp, Duration window);

    /** IP 별 검증 요청 횟수를 1 올리고 누적값을 돌려준다. 저장소 장애면 0 (fail-open). */
    long increaseIpVerifyCount(EmailCodePurpose purpose, String clientIp, Duration window);
}
