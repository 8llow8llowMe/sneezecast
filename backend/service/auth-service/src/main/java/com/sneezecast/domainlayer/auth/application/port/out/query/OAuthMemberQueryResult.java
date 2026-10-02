package com.sneezecast.domainlayer.auth.application.port.out.query;

import lombok.Builder;

/**
 * 소셜 제공자가 알려 준 사용자 정보. 제공자 응답 DTO 는 어댑터 밖으로 내보내지 않고 이 중립 모델로 바꾼다. 받는 항목은 이메일 · 닉네임뿐이다(프로필 이미지 ·
 * 제공자 회원 ID 는 받지 않는다 — 최소 수집).
 *
 * @param email         이메일. 사용자가 제공에 동의하지 않았거나 없으면 null
 * @param emailVerified 제공자가 이메일 소유를 인증했다고 알렸는지 (카카오 {@code is_email_verified})
 * @param emailValid    제공자가 이메일이 유효하다고 알렸는지 (카카오 {@code is_email_valid} — 다른 계정에 재할당된 이메일이면 false)
 * @param nickname      닉네임. 없으면 null
 */
@Builder
public record OAuthMemberQueryResult(
    String email,
    boolean emailVerified,
    boolean emailValid,
    String nickname
) {

    /** 이메일 · 닉네임을 로그 · 예외 메시지에 흘리지 않는다. */
    @Override
    public String toString() {
        return "OAuthMemberQueryResult[email=" + (email == null ? "null" : "****") + ", emailVerified=" + emailVerified + ", emailValid=" + emailValid
            + ", nickname=****]";
    }
}
