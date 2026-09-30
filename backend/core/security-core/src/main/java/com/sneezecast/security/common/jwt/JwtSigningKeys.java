package com.sneezecast.security.common.jwt;

import java.nio.charset.StandardCharsets;

/**
 * HS512 서명 키 설정의 공통 규칙. 키를 담는 설정 record({@code JwtAuthProperties} · {@code JwtResourceServerProperties})가
 * 생성 시점에 부른다 — 바인딩에서 던지므로 짧은 키로는 서비스가 뜨지 않는다.
 *
 * <p>짧은 키로 뜨면 jjwt · Nimbus 가 발급 · 검증마다 키를 거부해 health 는 UP 인데 모든 토큰이 401 이 된다.
 * 길이는 맞는데 값만 서비스끼리 다른 키는 여기서 잡지 못한다 (같은 {@code JWT_ACCESS_KEY} 를 주입하는 배포 규칙에 기댄다).
 *
 * <p>길이는 서명 · 검증과 같은 해석(UTF-8 바이트)으로 잰다. 예외 메시지에는 설정 이름과 env 이름만 싣고 키 값은 싣지 않는다.
 */
public final class JwtSigningKeys {

    /** HS512 최소 키 길이 — 해시 출력 길이(512비트)와 같다. */
    public static final int MIN_HS512_KEY_BYTES = 64;

    /** 설정 record 의 {@code toString()} 에서 키 자리에 찍는 값. 길이도 드러내지 않는다. */
    public static final String MASKED = "****";

    private JwtSigningKeys() {
    }

    /**
     * @param propertyName 설정 이름 (예: {@code jwt.access-key})
     * @param envName      배포 시 값을 주입하는 환경변수 이름 (예: {@code JWT_ACCESS_KEY})
     * @throws IllegalArgumentException 키가 없거나, 공백뿐이거나, UTF-8 {@value #MIN_HS512_KEY_BYTES}바이트 미만
     */
    public static void requireHs512Key(String propertyName, String envName, String key) {
        if (key == null || key.isBlank() || key.getBytes(StandardCharsets.UTF_8).length < MIN_HS512_KEY_BYTES) {
            throw new IllegalArgumentException(
                propertyName + " (" + envName + ") 는 공백이 아닌 UTF-8 " + MIN_HS512_KEY_BYTES + "바이트 이상이어야 합니다 (HS512).");
        }
    }
}
