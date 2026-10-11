package com.sneezecast.domainlayer.member.application.service.support;

import com.sneezecast.global.properties.WithdrawnEmailProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 탈퇴 회원의 이메일을 되돌릴 수 없는 다이제스트로 바꾼다 — {@code HMAC-SHA256(pepper, "{memberId}:" + EmailNormalizer.normalize(email))} 의 hex
 * 64자(entity-design §1-1). {@code member.email}(length 100)에 그대로 들어가 스키마를 건드리지 않고 원문 자리를 대신한다.
 *
 * <p><b>재가입 차단에 쓰지 않는다.</b> 원문을 비우면서 {@code uk_member_email} 을 지키려는 것이고, 원문이 사라지므로 같은 이메일로 바로 다시 가입할 수
 * 있다(2026-10-01 결정 — hondigagae 의 다이제스트 대조 차단은 가져오지 않는다).
 *
 * <p><b>입력에 회원 ID 를 넣는다.</b> 이메일만 넣으면 "탈퇴 → 같은 이메일로 재가입 → 다시 탈퇴" 에서 두 탈퇴 행의 다이제스트가 같아져, 앞 행이 파기되기
 * 전(30일 + 보고 파기)에는 둘째 탈퇴가 {@code uk_member_email} 에 막힌다. 재가입을 허용하므로 이 흐름은 정상이다. 회원 ID 는 행마다 다르니 다이제스트도
 * 행마다 다르고, pepper 를 아는 쪽은 여전히 "이 탈퇴 행이 그 이메일이었는가" 를 대조할 수 있다.
 *
 * <p>정규화는 {@link EmailNormalizer} 하나만 쓴다. pepper 를 바꾸면 기존 다이제스트와 대조할 수 없어({@link WithdrawnEmailProperties}) 기동 때 지문을
 * INFO 로 남긴다.
 */
@Slf4j
@Component
public class WithdrawnEmailHasher {

    private static final String ALGORITHM = "HmacSHA256";

    /**
     * 지문 계산에 쓰는 고정 입력. 값에는 뜻이 없고 고정이라는 성질만 쓴다 — 입력이 고정이면 출력은 pepper 에만 달려, 배포 사이에 지문이 달라졌다는 것은
     * pepper 가 바뀌었다는 뜻이다. 실제 이메일을 쓰지 않는 이유는 로그의 값이 누군가의 다이제스트와 같아지면 안 되기 때문이다. <b>바꾸면 이전 배포 로그와
     * 비교할 수 없으니 바꾸지 않는다.</b>
     */
    private static final String FINGERPRINT_PROBE = "sneezecast-withdrawn-email-pepper-fingerprint";

    /** 지문으로 내보내는 hex 길이(32비트) — 교체를 알아채기에 충분하고 pepper 를 되찾기에는 턱없이 모자라다. */
    private static final int FINGERPRINT_LENGTH = 8;

    /** {@link Mac} 과 달리 키 스펙은 불변이라 필드로 들고 있어도 안전하다. */
    private final SecretKeySpec secretKey;
    private final String fingerprint;

    /** pepper 의 비었음 · 길이 검증은 {@link WithdrawnEmailProperties} 가 기동 때 한다. pepper 자체는 어디에도 남기지 않는다. */
    public WithdrawnEmailHasher(WithdrawnEmailProperties properties) {
        this.secretKey = new SecretKeySpec(properties.pepper().getBytes(StandardCharsets.UTF_8), ALGORITHM);
        this.fingerprint = digest(FINGERPRINT_PROBE).substring(0, FINGERPRINT_LENGTH);
        log.info("withdrawn email pepper fingerprint={}", fingerprint);
    }

    /** 지금 pepper 의 지문(hex 8자). 같은 pepper 면 늘 같고 다른 pepper 면 다르다. */
    public String fingerprint() {
        return fingerprint;
    }

    /**
     * 회원 ID 와 정규화한 이메일의 다이제스트(hex 64자, 소문자). 같은 pepper · 같은 회원 · 같은 정규화 결과면 늘 같고, 회원이 다르면 같은 이메일이어도 다르다.
     */
    public String hash(long memberId, String email) {
        return digest(memberId + ":" + EmailNormalizer.normalize(email));
    }

    /** {@link Mac} 은 스레드 안전하지 않아 호출마다 만든다(탈퇴 빈도에서 풀링은 의미가 없다). */
    private String digest(String input) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(secretKey);
            return HexFormat.of().formatHex(mac.doFinal(input.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            // HmacSHA256 은 표준 JDK 알고리즘이라 정상 환경에서는 오지 않는다.
            throw new IllegalStateException("withdrawn email digest unavailable", exception);
        }
    }
}
