package com.sneezecast.domainlayer.auth.application.service.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

/**
 * 비밀번호 재설정 토큰. 인증코드를 맞힌 쪽에만 주고, 재설정 권한을 이메일이 아니라 이 토큰에 묶는다 — 이메일만 아는 사람이 인증 표시를 이용해
 * 비밀번호를 바꾸지 못하게 한다.
 *
 * <p>값은 {@link SecureRandom} 32바이트(256비트)의 base64url(패딩 없음, 43자)이라 추측할 수 없다. 저장소에는 원문 대신 {@link #hash} 만 둔다.
 */
@Component
public class PasswordResetTokenGenerator {

    static final int TOKEN_BYTES = 32;

    private final SecureRandom secureRandom = new SecureRandom();

    public String generate() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * 저장 키로 쓰는 SHA-256 소문자 hex(64자). 토큰이 256비트 난수라 솔트 · 느린 해시가 필요 없다 — 원문을 되찾을 수 없으면 충분하다.
     */
    public static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            // 모든 JVM 이 SHA-256 을 갖춰야 한다(Java SE 명세). 없으면 실행 환경이 깨진 것이다.
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
