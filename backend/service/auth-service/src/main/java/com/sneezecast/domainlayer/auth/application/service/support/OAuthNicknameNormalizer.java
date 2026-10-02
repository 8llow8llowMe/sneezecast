package com.sneezecast.domainlayer.auth.application.service.support;

import com.sneezecast.domainlayer.member.domain.policy.MemberInputPolicy;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 소셜 제공자가 준 닉네임을 회원 닉네임 규칙(앞뒤 공백을 지운 뒤 {@value MemberInputPolicy#NICKNAME_MIN_LENGTH}~{@value MemberInputPolicy#NICKNAME_MAX_LENGTH}자,
 * {@link MemberInputPolicy})에 맞춘다. 제공자 닉네임은 사용자가 고른 값이 아니라 가입을 막지 않고 고쳐서 받는다 — 내 정보에서 바꿀 수 있다.
 *
 * <ul>
 *   <li>앞뒤 공백을 지운다.</li>
 *   <li>상한을 넘으면 <b>코드포인트 기준</b> 앞 {@value MemberInputPolicy#NICKNAME_MAX_LENGTH}자만 남긴다 — UTF-16 단위로 자르면 이모지 같은 보조 문자가
 *       반쪽(서로게이트 하나)으로 잘려 깨진 문자가 저장된다. 자른 뒤 끝에 공백이 남으면 다시 지운다.</li>
 *   <li>그래도 하한보다 짧으면(없음 · 공백뿐 · 한 글자) {@value #FALLBACK_PREFIX} + 무작위 숫자 4자리로 정한다.</li>
 * </ul>
 */
public final class OAuthNicknameNormalizer {

    static final String FALLBACK_PREFIX = "동네이웃";
    private static final int FALLBACK_DIGITS_BOUND = 10_000;

    private OAuthNicknameNormalizer() {
    }

    public static String normalize(String nickname) {
        String stripped = nickname == null ? "" : nickname.strip();
        if (stripped.codePointCount(0, stripped.length()) > MemberInputPolicy.NICKNAME_MAX_LENGTH) {
            stripped = stripped.substring(0, stripped.offsetByCodePoints(0, MemberInputPolicy.NICKNAME_MAX_LENGTH)).strip();
        }
        if (stripped.codePointCount(0, stripped.length()) < MemberInputPolicy.NICKNAME_MIN_LENGTH) {
            return FALLBACK_PREFIX + "%04d".formatted(ThreadLocalRandom.current().nextInt(FALLBACK_DIGITS_BOUND));
        }
        return stripped;
    }
}
