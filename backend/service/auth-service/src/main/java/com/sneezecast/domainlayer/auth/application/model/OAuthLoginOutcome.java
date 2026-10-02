package com.sneezecast.domainlayer.auth.application.model;

/** 카카오 로그인 콜백의 결과. 응답의 {@code result} 에 {@code name()} 으로 나간다. */
public enum OAuthLoginOutcome {
    /** 카카오 로그인이 연결된 회원 — 토큰을 발급했다. */
    LOGGED_IN,
    /** 처음 온 이메일 — 가입표를 쿠키로 내렸고, 동의를 받은 뒤 가입한다. */
    SIGNUP_REQUIRED,
    /** 같은 이메일의 이메일 계정이 있다 — 연결 확인표를 쿠키로 내렸고, 확인을 받은 뒤 연결한다. */
    LINK_REQUIRED
}
