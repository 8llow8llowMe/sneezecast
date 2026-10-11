package com.sneezecast.domainlayer.member.application.service.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.sneezecast.global.properties.WithdrawnEmailProperties;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WithdrawnEmailHasherTest {

    private static final String PEPPER = "withdrawn-email-pepper-0123456789abcdef";
    private static final long MEMBER_ID = 1843956734582784L;

    private final WithdrawnEmailHasher hasher = new WithdrawnEmailHasher(new WithdrawnEmailProperties(PEPPER));

    @Test
    @DisplayName("다이제스트는 HMAC-SHA256(pepper, \"{memberId}:\" + 정규화한 이메일) 의 소문자 hex 64자다 — member.email(100자)에 들어간다")
    void digestIsHmacOfMemberIdAndNormalizedEmail() throws Exception {
        String digest = hasher.hash(MEMBER_ID, "user@example.com");

        assertThat(digest).hasSize(64).matches("[0-9a-f]{64}").isEqualTo(hmac(PEPPER, MEMBER_ID + ":user@example.com"));
        assertThat(digest).doesNotContain("@").doesNotContain("user");
    }

    @Test
    @DisplayName("EmailNormalizer 로 정규화한 뒤 계산한다 — 대소문자 · 앞뒤 공백이 달라도 같은 다이제스트다")
    void normalizesBeforeHashing() {
        assertThat(hasher.hash(MEMBER_ID, "  User@Example.COM ")).isEqualTo(hasher.hash(MEMBER_ID, "user@example.com"));
    }

    @Test
    @DisplayName("같은 pepper · 회원 · 이메일이면 늘 같다(결정적) — 인스턴스를 새로 만들어도 같다")
    void isDeterministic() {
        WithdrawnEmailHasher another = new WithdrawnEmailHasher(new WithdrawnEmailProperties(PEPPER));

        assertThat(hasher.hash(MEMBER_ID, "user@example.com")).isEqualTo(hasher.hash(MEMBER_ID, "user@example.com"))
            .isEqualTo(another.hash(MEMBER_ID, "user@example.com"));
    }

    @Test
    @DisplayName("같은 이메일이어도 회원이 다르면 다르다 — 탈퇴 → 재가입 → 다시 탈퇴한 두 행이 uk_member_email 에 막히지 않는다")
    void differsPerMember() {
        assertThat(hasher.hash(MEMBER_ID, "user@example.com")).isNotEqualTo(hasher.hash(MEMBER_ID + 1, "user@example.com"));
    }

    @Test
    @DisplayName("pepper 가 다르면 다이제스트 · 지문이 모두 다르다 — 지문은 hex 8자이고 pepper 로만 정해진다")
    void pepperChangesDigestAndFingerprint() {
        WithdrawnEmailHasher other = new WithdrawnEmailHasher(new WithdrawnEmailProperties("another-withdrawn-email-pepper-9876543210"));

        assertThat(other.hash(MEMBER_ID, "user@example.com")).isNotEqualTo(hasher.hash(MEMBER_ID, "user@example.com"));
        assertThat(hasher.fingerprint()).matches("[0-9a-f]{8}").isNotEqualTo(other.fingerprint())
            .isEqualTo(new WithdrawnEmailHasher(new WithdrawnEmailProperties(PEPPER)).fingerprint());
    }

    private static String hmac(String key, String input) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(input.getBytes(StandardCharsets.UTF_8)));
    }
}
