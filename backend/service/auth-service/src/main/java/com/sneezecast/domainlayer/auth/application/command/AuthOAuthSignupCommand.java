package com.sneezecast.domainlayer.auth.application.command;

import lombok.Builder;

/**
 * 소셜 가입 명령. 이메일 · 닉네임은 요청이 아니라 가입표에서 꺼낸다 — 사용자가 바꿔 보낼 수 없다. 건강정보 동의는 이메일 가입과 같이 가입 뒤 별도 API 로
 * 받는다.
 *
 * @param signupTicket 카카오 로그인 콜백이 쿠키로 내린 가입표. 없으면 null
 * @param deviceLabel  User-Agent 를 "OS · 브라우저" 로 줄인 기기 이름
 */
@Builder
public record AuthOAuthSignupCommand(
    String signupTicket,
    boolean termsAgreed,
    boolean privacyAgreed,
    boolean ageOver19Confirmed,
    String deviceLabel
) {

    /** 가입표를 로그 · 예외 메시지에 흘리지 않는다. */
    @Override
    public String toString() {
        return "AuthOAuthSignupCommand[signupTicket=****, termsAgreed=" + termsAgreed + ", privacyAgreed=" + privacyAgreed + ", ageOver19Confirmed="
            + ageOver19Confirmed + ", deviceLabel=" + deviceLabel + "]";
    }
}
