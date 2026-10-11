package com.sneezecast.global.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 탈퇴 회원의 이메일을 다이제스트로 바꿀 때 쓰는 비밀값 ({@code member.withdrawn-email.pepper} ← env {@code WITHDRAWN_EMAIL_PEPPER}).
 *
 * <p>탈퇴하면 {@code member.email} 원문을 {@code HMAC-SHA256(pepper, "{memberId}:" + 정규화한 이메일)} 의 hex 64자로 바꾼다(entity-design §1-1). 원문을 비우면서
 * {@code uk_member_email} 을 지키고, 같은 이메일로 바로 다시 가입할 수 있게 하려는 것이다 — <b>재가입 차단에는 쓰지 않는다</b>(sneezecast 는 탈퇴 즉시
 * 재가입을 허용한다). pepper 가 필요한 이유는 이메일 공간이 열거 가능해서다 — 단순 SHA-256 은 흔한 주소 목록을 돌려 원문을 되찾는다.
 *
 * <p><b>한번 정하면 바꾸지 않는다.</b> 바꾸면 이미 저장된 탈퇴 행의 다이제스트와 새로 계산한 값을 대조할 수 없고, 원문이 없어 다시 계산할 수도 없다.
 * 교체를 사후에라도 알아채도록 기동 때 지문을 남긴다({@code WithdrawnEmailHasher}).
 *
 * <p>기본값이 없다. <b>비었거나 공백이거나 env 가 풀리지 않은 자리표시자({@code ${...}})이거나 32자 미만이면 기동에서 실패</b>한다 — 비밀 없이 해시만 도는
 * 상태는 다이제스트가 정상으로 보여 탈퇴 데이터가 쌓인 뒤에야 드러난다. 값은 Vault {@code {env}/auth-service} 에 둔다(deploy-guide §6).
 *
 * @param pepper HMAC 키. 앞뒤 공백을 지운 값을 쓴다. toString 에서 가린다
 */
@ConfigurationProperties(prefix = "member.withdrawn-email")
public record WithdrawnEmailProperties(
    String pepper
) {

    /** HMAC 키로 받을 최소 길이. 이보다 짧으면 비밀로 보기 어렵다. */
    public static final int MINIMUM_PEPPER_LENGTH = 32;

    private static final String KEY = "member.withdrawn-email.pepper (WITHDRAWN_EMAIL_PEPPER)";

    public WithdrawnEmailProperties {
        // Binder 는 풀리지 않은 자리표시자를 문자열 그대로 넘긴다 — env 가 빠진 배포가 "${WITHDRAWN_EMAIL_PEPPER}" 를 키로 쓰지 않게 막는다.
        if (pepper == null || pepper.isBlank() || pepper.contains("${")) {
            throw new IllegalStateException(KEY + " 가 비어 있습니다.");
        }
        pepper = pepper.strip();
        // 메시지에는 값도 길이도 넣지 않는다 (coding-conventions §10).
        if (pepper.length() < MINIMUM_PEPPER_LENGTH) {
            throw new IllegalStateException(KEY + " 는 " + MINIMUM_PEPPER_LENGTH + "자 이상이어야 합니다.");
        }
    }

    /** 비밀값은 값도 길이도 드러내지 않는다 (coding-conventions §10). */
    @Override
    public String toString() {
        return "WithdrawnEmailProperties[pepper=****]";
    }
}
