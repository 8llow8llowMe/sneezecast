package com.sneezecast.domainlayer.report.application.service;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 회원 ID 를 가명 보고자 키로 바꾼다. surveillance 는 {@code member_id} 를 저장하지 않고 이 키로만 보고자를 식별한다.
 *
 * <p><b>산출식은 고정 계약이다</b> (architecture-guide §9) — {@code reporter_key = lowercase-hex(HMAC-SHA256(key = pepper 의 UTF-8 바이트,
 * message = memberId 의 10진 문자열 UTF-8 바이트))}. 바꾸면 같은 회원의 주간 보고가 다른 보고자로 갈라진다. 변경은 기존 데이터 변환 계획과
 * 함께만 한다.
 *
 * <p>pepper 는 기동 시점에 검사한다. 없거나 {@value #MIN_PEPPER_LENGTH}자 미만이면 기동 실패. 로그에는 운영자가 배포 간 pepper 가 같은지
 * 대조할 수 있도록 지문(SHA-256 앞 {@value #FINGERPRINT_LENGTH}자)만 남긴다. pepper 값은 로그 · 예외 메시지 어디에도 싣지 않는다.
 */
@Slf4j
@Component
public class ReporterKeyGenerator {

    static final int MIN_PEPPER_LENGTH = 32;
    static final int FINGERPRINT_LENGTH = 8;

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String FINGERPRINT_ALGORITHM = "SHA-256";
    private static final HexFormat LOWERCASE_HEX = HexFormat.of();

    private final SecretKeySpec pepperKey;

    public ReporterKeyGenerator(ReporterKeyProperties properties) {
        String pepper = properties.pepper();
        if (pepper == null || pepper.isBlank() || pepper.length() < MIN_PEPPER_LENGTH) {
            throw new IllegalStateException(
                "surveillance.reporter-key.pepper (REPORTER_KEY_PEPPER) 는 공백이 아닌 " + MIN_PEPPER_LENGTH + "자 이상이어야 합니다.");
        }

        byte[] pepperBytes = pepper.getBytes(StandardCharsets.UTF_8);
        this.pepperKey = new SecretKeySpec(pepperBytes, HMAC_ALGORITHM);
        // 알고리즘 · 키 문제를 첫 보고가 아니라 기동에서 드러낸다.
        newMac();

        log.info("reporter key pepper loaded fingerprint={}", fingerprint(pepperBytes));
    }

    /**
     * @param memberId auth 회원 ID (Snowflake, 양수)
     * @return 64자 소문자 hex
     * @throws IllegalArgumentException memberId 가 0 이하인 경우 — 기본값 0 이 새어 들면 서로 다른 회원이 한 보고자로 합쳐진다
     */
    public String reporterKey(long memberId) {
        if (memberId <= 0) {
            // 회원 식별정보라 값은 메시지에 싣지 않는다.
            throw new IllegalArgumentException("memberId must be positive");
        }
        byte[] message = Long.toString(memberId).getBytes(StandardCharsets.UTF_8);
        return LOWERCASE_HEX.formatHex(newMac().doFinal(message));
    }

    // Mac 은 스레드 안전하지 않으므로 호출마다 새로 만든다. 키 스펙은 불변이라 공유한다.
    private Mac newMac() {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(pepperKey);
            return mac;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(HMAC_ALGORITHM + " is unavailable", e);
        }
    }

    private static String fingerprint(byte[] pepperBytes) {
        try {
            byte[] digest = MessageDigest.getInstance(FINGERPRINT_ALGORITHM).digest(pepperBytes);
            return LOWERCASE_HEX.formatHex(digest).substring(0, FINGERPRINT_LENGTH);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(FINGERPRINT_ALGORITHM + " is unavailable", e);
        }
    }
}
