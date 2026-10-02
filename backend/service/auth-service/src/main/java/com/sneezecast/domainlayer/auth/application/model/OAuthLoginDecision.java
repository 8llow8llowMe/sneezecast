package com.sneezecast.domainlayer.auth.application.model;

import com.sneezecast.domainlayer.member.domain.model.Member;

/**
 * 카카오 로그인 콜백에서 회원을 찾아 정한 다음 단계. 결과마다 채워지는 필드가 다르다.
 *
 * @param member      {@code LOGGED_IN} — 토큰을 발급할 회원
 * @param ticket      {@code SIGNUP_REQUIRED} · {@code LINK_REQUIRED} — 쿠키로 내릴 표 원문(저장소에는 해시만 있다)
 * @param nickname    {@code SIGNUP_REQUIRED} — 가입하면 저장할 닉네임
 * @param maskedEmail {@code LINK_REQUIRED} — 연결할 계정의 가린 이메일
 */
public record OAuthLoginDecision(
    OAuthLoginOutcome outcome,
    Member member,
    String ticket,
    String nickname,
    String maskedEmail
) {

    public static OAuthLoginDecision loggedIn(Member member) {
        return new OAuthLoginDecision(OAuthLoginOutcome.LOGGED_IN, member, null, null, null);
    }

    public static OAuthLoginDecision signupRequired(String ticket, String nickname) {
        return new OAuthLoginDecision(OAuthLoginOutcome.SIGNUP_REQUIRED, null, ticket, nickname, null);
    }

    public static OAuthLoginDecision linkRequired(String ticket, String maskedEmail) {
        return new OAuthLoginDecision(OAuthLoginOutcome.LINK_REQUIRED, null, ticket, null, maskedEmail);
    }

    /** 표 원문 · 회원 정보를 로그 · 예외 메시지에 흘리지 않는다. */
    @Override
    public String toString() {
        return "OAuthLoginDecision[outcome=" + outcome + ", memberId=" + (member == null ? "null" : member.id()) + ", ticket=****]";
    }
}
