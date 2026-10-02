package com.sneezecast.global.properties;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 로그인 세션(기기) 설정 ({@code auth.session.*}). 값은 application.yml 의 env 자리표시자 기본값이 정본이다.
 *
 * <p>0 이하 값은 기동에서 실패시킨다 ({@link EmailSendLimitProperties} 와 같은 규칙).
 *
 * @param maxDevices     회원당 동시 로그인 기기 수. 넘으면 마지막 사용이 가장 오래된 세션부터 밀어낸다 (밀려난 기기의 access 도 바로 폐기한다)
 * @param rotationGrace  refresh 회전 직후 <b>직전 토큰</b>을 "동시 재발급 경합" 으로 봐주는 시간. 이 안에 직전 토큰이 오면 세션을 건드리지 않고
 *                       409(AUTH_016)로 재시도를 유도하고, 지나서 오면 재사용(탈취 의심)으로 보고 세션을 폐기한다. 여러 탭이 같은 쿠키로 동시에
 *                       재발급하는 경우를 흡수할 만큼만 짧게 둔다
 */
@ConfigurationProperties(prefix = "auth.session")
public record AuthSessionProperties(
    int maxDevices,
    Duration rotationGrace
) {

    public AuthSessionProperties {
        if (maxDevices <= 0) {
            throw new IllegalStateException("auth.session.max-devices 는 0 보다 커야 합니다: " + maxDevices);
        }
        if (rotationGrace == null || rotationGrace.isZero() || rotationGrace.isNegative()) {
            throw new IllegalStateException("auth.session.rotation-grace 는 0 보다 커야 합니다: " + rotationGrace);
        }
    }
}
