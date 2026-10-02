package com.sneezecast.domainlayer.auth.application.model;

/**
 * 이메일 인증코드를 어디에 쓰는지. 한도 · 수명({@code auth.email-send.*})과 발송 · 검증 규칙은 목적과 무관하게 같고, <b>저장소 키만 목적별로
 * 나뉜다</b> — 가입 코드로 비밀번호를 재설정하거나 그 반대가 되지 않게, 그리고 한쪽의 쿨다운 · IP 상한이 다른 쪽을 막지 않게 한다.
 */
public enum EmailCodePurpose {
    // 회원가입 이메일 인증
    SIGNUP,
    // 비밀번호 재설정
    PASSWORD_RESET
}
