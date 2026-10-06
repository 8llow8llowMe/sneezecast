package com.sneezecast.domainlayer.member.adapter.in.web.support;

import org.springframework.http.ResponseCookie;

/**
 * member 컨트롤러가 refresh 토큰 쿠키를 <b>지울 때만</b> 쓰는 Set-Cookie. 건강정보 동의 철회로 모든 기기를 로그아웃시킨 뒤, 이 브라우저의 refresh 도
 * 지워 화면이 재발급으로 다시 로그인 상태가 되지 않게 한다.
 *
 * <p>쿠키의 정본은 auth 의 {@code RefreshCookieProvider} 다. 그 클래스를 직접 쓰면 member 웹 계층이 auth 를 import 하게 된다 — member → auth 는
 * {@code member/adapter/out/auth} 어댑터로만 잇는다(modules.md 컨텍스트 의존 방향). 쿠키 삭제는 응답 헤더라 out 포트로 돌릴 수도 없어, 같은
 * 이름 · 속성을 여기 두고 {@code MemberRefreshCookieTest} 가 auth 쪽 {@code clear()} 와 같은 헤더인지 고정한다. 이름 · Path · SameSite 가 하나라도
 * 다르면 브라우저는 다른 쿠키로 보고 지우지 않는다.
 *
 * <p>Path 가 {@code /api/v1/auth} 라도 {@code /api/v1/members/**} 응답에서 지울 수 있다 — 같은 호스트의 Set-Cookie 는 Path 를 요청 경로와 다르게
 * 줄 수 있다.
 */
public final class MemberRefreshCookie {

    static final String REFRESH_TOKEN_COOKIE = "refreshToken";
    static final String AUTH_PATH = "/api/v1/auth";
    private static final String SAME_SITE = "Strict";

    private MemberRefreshCookie() {
    }

    /** {@code refreshToken=; Max-Age=0} — HttpOnly · Secure · SameSite=Strict · Path=/api/v1/auth. */
    public static ResponseCookie clear() {
        return ResponseCookie.from(REFRESH_TOKEN_COOKIE, "")
            .httpOnly(true)
            .secure(true)
            .sameSite(SAME_SITE)
            .path(AUTH_PATH)
            .maxAge(0)
            .build();
    }
}
