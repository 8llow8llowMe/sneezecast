package com.sneezecast.domainlayer.auth.application.service.processor;

import com.sneezecast.domainlayer.auth.application.model.EmailCodePurpose;
import com.sneezecast.domainlayer.auth.application.port.out.EmailVerificationStorePort;
import java.time.Duration;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 이메일 인증 저장소 가짜 — 가입 · 재설정 처리기 테스트가 함께 쓴다. TTL 은 흉내 내지 않는다(만료는 "키가 없다" 로 표현한다).
 *
 * <p>한 테스트는 한 목적만 다루므로 키는 이메일 · IP 만으로 잡고, 불린 목적을 {@link #purposes} 에 모아 "다른 목적의 키를 건드리지 않았다" 를 본다.
 * 목적별 키 이름이 실제로 갈리는지는 {@code RedisEmailVerificationStoreAdapterTest} 가 본다.
 */
class FakeEmailVerificationStore implements EmailVerificationStorePort {

    final Map<String, String> codes = new HashMap<>();
    final Map<String, Duration> codeTtls = new HashMap<>();
    final Map<String, Duration> verified = new HashMap<>();
    final Set<String> cooldowns = new HashSet<>();
    final Map<String, Long> failures = new HashMap<>();
    final Map<String, Long> ipSendCounts = new HashMap<>();
    final Map<String, Long> ipVerifyCounts = new HashMap<>();
    final Set<EmailCodePurpose> purposes = EnumSet.noneOf(EmailCodePurpose.class);

    @Override
    public void saveCode(EmailCodePurpose purpose, String email, String code, Duration ttl) {
        purposes.add(purpose);
        codes.put(email, code);
        codeTtls.put(email, ttl);
    }

    @Override
    public Optional<String> findCode(EmailCodePurpose purpose, String email) {
        purposes.add(purpose);
        return Optional.ofNullable(codes.get(email));
    }

    @Override
    public void deleteCode(EmailCodePurpose purpose, String email) {
        purposes.add(purpose);
        codes.remove(email);
    }

    @Override
    public void saveVerified(String email, Duration ttl) {
        verified.put(email, ttl);
    }

    @Override
    public boolean isVerified(String email) {
        return verified.containsKey(email);
    }

    @Override
    public void deleteVerified(String email) {
        verified.remove(email);
    }

    @Override
    public boolean tryAcquireCooldown(EmailCodePurpose purpose, String email, Duration ttl) {
        purposes.add(purpose);
        return cooldowns.add(email);
    }

    @Override
    public long increaseVerifyFailureCount(EmailCodePurpose purpose, String email, Duration ttl) {
        purposes.add(purpose);
        return failures.merge(email, 1L, Long::sum);
    }

    @Override
    public void clearVerifyFailures(EmailCodePurpose purpose, String email) {
        purposes.add(purpose);
        failures.remove(email);
    }

    @Override
    public long findIpSendCount(EmailCodePurpose purpose, String clientIp) {
        purposes.add(purpose);
        return ipSendCounts.getOrDefault(clientIp, 0L);
    }

    @Override
    public long increaseIpSendCount(EmailCodePurpose purpose, String clientIp, Duration window) {
        purposes.add(purpose);
        return ipSendCounts.merge(clientIp, 1L, Long::sum);
    }

    @Override
    public long increaseIpVerifyCount(EmailCodePurpose purpose, String clientIp, Duration window) {
        purposes.add(purpose);
        return ipVerifyCounts.merge(clientIp, 1L, Long::sum);
    }
}
