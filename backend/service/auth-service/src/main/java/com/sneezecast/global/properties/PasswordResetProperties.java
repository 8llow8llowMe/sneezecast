package com.sneezecast.global.properties;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 비밀번호 재설정 설정 ({@code auth.password-reset.*}). 값은 application.yml 의 env 자리표시자 기본값이 정본이다. 재설정 코드의 한도 · 수명은
 * 가입 인증과 같은 {@link EmailSendLimitProperties} 를 쓴다.
 *
 * <p>0 이하 값은 기동에서 실패시킨다 ({@link EmailSendLimitProperties} 와 같은 규칙).
 *
 * @param tokenTtl 코드를 맞힌 뒤 받는 재설정 토큰의 수명 — 이 안에 새 비밀번호를 정해야 한다
 */
@ConfigurationProperties(prefix = "auth.password-reset")
public record PasswordResetProperties(
    Duration tokenTtl
) {

    public PasswordResetProperties {
        if (tokenTtl == null || tokenTtl.isZero() || tokenTtl.isNegative()) {
            throw new IllegalStateException("auth.password-reset.token-ttl 는 0 보다 커야 합니다: " + tokenTtl);
        }
    }
}
