package com.sneezecast.domainlayer.auth.application.model;

import com.sneezecast.domainlayer.member.domain.enums.OAuthProvider;

/**
 * 카카오로 처음 온 사용자의 가입표에 묶어 두는 값. 가입 동의 전에는 회원 행을 만들지 않으므로(개인정보 수집 동의가 먼저) 카카오에서 받은 값을 여기에만
 * 잠시 둔다.
 *
 * @param email    정규화한 이메일 (카카오가 인증 · 유효하다고 알린 값)
 * @param nickname 정규화한 닉네임 — 가입하면 이 값을 그대로 저장한다
 */
public record OAuthSignupTicket(
    OAuthProvider provider,
    String email,
    String nickname
) {

    /** 이메일을 로그 · 예외 메시지에 흘리지 않는다. */
    @Override
    public String toString() {
        return "OAuthSignupTicket[provider=" + provider + ", email=****, nickname=****]";
    }
}
