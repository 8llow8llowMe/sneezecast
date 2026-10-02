package com.sneezecast.domainlayer.auth.adapter.in.web.support;

import com.sneezecast.security.auth.jwt.JwtAuthProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * refresh 토큰 쿠키를 굽고 지운다. 쿠키 속성의 단일 소유자다.
 *
 * <ul>
 *   <li>{@code HttpOnly} — 스크립트가 읽지 못한다(XSS 로 refresh 가 새지 않는다). access token 은 응답 본문으로 받아 메모리에만 둔다.</li>
 *   <li>{@code Secure} — <b>항상 켠다.</b> sneezecast 는 로컬 프로필 없이 dev · prod 모두 https 로만 서비스한다.</li>
 *   <li>{@code SameSite=Strict} — 웹({@code dev}/{@code www.sneezecast.com})과 API({@code api-dev}/{@code api.sneezecast.com})는 등록 도메인이
 *       같은 <b>같은 사이트</b>라 Strict 로도 쿠키가 실린다. 다른 사이트에서 시작된 요청에는 실리지 않아 CSRF 표면이 없다.
 *       <b>한계:</b> FE 로컬 개발 서버({@code http://localhost:*})에서 dev API 를 부르면 교차 사이트라 쿠키가 실리지 않는다(Secure 라 http 에서는
 *       저장도 안 된다) — 로컬에서는 재발급 · 로그아웃 쿠키 흐름을 확인할 수 없고, dev 웹에서 확인한다.</li>
 *   <li>{@code Path=/api/v1/auth} — 재발급 · 로그아웃 · 세션 API 에만 실린다. 다른 API 요청마다 refresh 가 오가지 않는다.</li>
 *   <li>{@code Max-Age} = refresh 만료. 지울 때는 0.</li>
 * </ul>
 * CORS 는 게이트웨이 · auth 모두 {@code allowCredentials=true} 라 브라우저가 {@code credentials: 'include'} 요청에 쿠키를 싣는다.
 */
@Component
@RequiredArgsConstructor
public class RefreshCookieProvider {

    /** 쿠키 이름의 단일 기준점. 컨트롤러의 {@code @CookieValue} 도 이 상수를 쓴다. */
    public static final String REFRESH_TOKEN_COOKIE = "refreshToken";
    static final String AUTH_PATH = "/api/v1/auth";
    private static final String SAME_SITE = "Strict";

    private final JwtAuthProperties jwtAuthProperties;

    public ResponseCookie create(String refreshToken) {
        return base(refreshToken).maxAge(jwtAuthProperties.refreshExpiration()).build();
    }

    public ResponseCookie clear() {
        return base("").maxAge(0).build();
    }

    private ResponseCookie.ResponseCookieBuilder base(String value) {
        return ResponseCookie.from(REFRESH_TOKEN_COOKIE, value)
            .httpOnly(true)
            .secure(true)
            .sameSite(SAME_SITE)
            .path(AUTH_PATH);
    }
}
