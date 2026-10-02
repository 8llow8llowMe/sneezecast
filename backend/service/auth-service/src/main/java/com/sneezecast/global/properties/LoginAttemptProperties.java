package com.sneezecast.global.properties;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 이메일 로그인 실패 제한 ({@code auth.login.*}). 값은 application.yml 의 env 자리표시자 기본값이 정본이다.
 *
 * <ul>
 *   <li>이메일 잠금 — {@code maxFailureCount} 번째 실패에서 {@code lockDuration} 동안 잠근다(기본 5회 · 10분, 시안 Login-email locked).
 *       실패 카운터의 수명도 {@code lockDuration} 이다 — 뜸하게 흩어진 실패는 저절로 사라져야 정상 사용자를 잠그지 않는다.</li>
 *   <li>IP 상한 — {@code ipWindow} 안에 {@code ipMaxFailureCount} 번 실패하면 그 IP 의 다음 시도를 막는다(기본 30회 · 1시간). 이메일을 바꿔
 *       가며 대입하는 시도를 늦춘다.</li>
 * </ul>
 * 둘 다 보조 방어라 저장소 장애에는 fail-open 이다.
 *
 * <p>0 이하 값은 기동에서 실패시킨다 — 상한 0 은 "모두 거부" 인지 "제한 없음" 인지 모호하고, 틀린 값이 조용히 기본값으로 바뀌면 운영자가
 * 설정이 먹었는지 알 수 없다 ({@link EmailSendLimitProperties} 와 같은 규칙).
 */
@ConfigurationProperties(prefix = "auth.login")
public record LoginAttemptProperties(
    int maxFailureCount,
    Duration lockDuration,
    int ipMaxFailureCount,
    Duration ipWindow
) {

    public LoginAttemptProperties {
        requirePositive("auth.login.max-failure-count", maxFailureCount);
        requirePositive("auth.login.lock-duration", lockDuration);
        requirePositive("auth.login.ip-max-failure-count", ipMaxFailureCount);
        requirePositive("auth.login.ip-window", ipWindow);
    }

    private static void requirePositive(String key, int value) {
        if (value <= 0) {
            throw new IllegalStateException(key + " 는 0 보다 커야 합니다: " + value);
        }
    }

    private static void requirePositive(String key, Duration value) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalStateException(key + " 는 0 보다 커야 합니다: " + value);
        }
    }
}
