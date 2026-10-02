package com.sneezecast.domainlayer.auth.application.service.support;

import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.stereotype.Component;

/**
 * 소셜 로그인 흐름의 일회용 값 — 인가 state · 가입표 · 연결 확인표. {@link SecureRandom} 32바이트(256비트)의 base64url(패딩 없음, 43자)이라 추측할 수 없다.
 *
 * <p>가입표 · 연결 확인표는 저장소에 원문 대신 {@link #hash} 만 둔다. 해시 규칙은 재설정 토큰과 같다({@link PasswordResetTokenGenerator#hash}) — 같은
 * 성질의 값(256비트 난수)에 해시 규칙을 두 벌 두지 않는다.
 */
@Component
public class OAuthOneTimeValueGenerator {

    static final int VALUE_BYTES = 32;

    private final SecureRandom secureRandom = new SecureRandom();

    public String generate() {
        byte[] bytes = new byte[VALUE_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** 저장 키로 쓰는 SHA-256 소문자 hex(64자). */
    public static String hash(String value) {
        return PasswordResetTokenGenerator.hash(value);
    }
}
