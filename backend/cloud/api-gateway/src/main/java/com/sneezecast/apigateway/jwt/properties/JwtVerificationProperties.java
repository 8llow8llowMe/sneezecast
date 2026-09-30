package com.sneezecast.apigateway.jwt.properties;

import java.nio.charset.StandardCharsets;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 게이트웨이 1차 검증 설정. {@code accessKey} 는 auth-service · 각 서비스와 같은 {@code JWT_ACCESS_KEY} 다.
 *
 * <p>생성 시점에 HS512 최소 길이(UTF-8 {@value #MIN_HS512_KEY_BYTES}바이트)를 검사한다 — 바인딩에서 실패하므로 짧은 키로는
 * 기동하지 않는다. 짧은 키로 뜨면 health 는 UP 인데 토큰 요청이 전부 401 이 된다. 게이트웨이는 security-core 를 쓰지 않으므로
 * security-core {@code JwtSigningKeys} 와 같은 규칙을 여기 둔다. 예외 메시지에는 설정 이름 · env 이름만 싣고 키 값은 싣지 않는다.
 */
@ConfigurationProperties(prefix = "jwt")
public record JwtVerificationProperties(
    String accessKey,
    boolean blacklistFailOpen
) {

    static final int MIN_HS512_KEY_BYTES = 64;

    public JwtVerificationProperties {
        if (accessKey == null || accessKey.isBlank() || accessKey.getBytes(StandardCharsets.UTF_8).length < MIN_HS512_KEY_BYTES) {
            throw new IllegalArgumentException(
                "jwt.access-key (JWT_ACCESS_KEY) 는 공백이 아닌 UTF-8 " + MIN_HS512_KEY_BYTES + "바이트 이상이어야 합니다 (HS512).");
        }
    }

    /** record 기본 toString 은 필드 값을 그대로 찍는다. 설정 덤프 · 디버그 로그로 키가 새지 않게 가린다 (길이도 숨긴다). */
    @Override
    public String toString() {
        return "JwtVerificationProperties[accessKey=****, blacklistFailOpen=" + blacklistFailOpen + "]";
    }
}
