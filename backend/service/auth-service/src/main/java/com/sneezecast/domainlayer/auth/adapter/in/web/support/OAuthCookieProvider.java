package com.sneezecast.domainlayer.auth.adapter.in.web.support;

import com.sneezecast.global.properties.OAuthLoginProperties;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * 카카오 로그인 흐름의 쿠키 3종(state · 가입표 · 연결 확인표)을 굽고 지운다. 쿠키 이름 · 속성의 단일 소유자다.
 *
 * <ul>
 *   <li>속성은 refresh 쿠키({@link RefreshCookieProvider})와 같다 — {@code HttpOnly} · {@code Secure} · {@code SameSite=Strict} ·
 *       {@code Path=/api/v1/auth}. 프론트 콜백 페이지({@code *.sneezecast.com})가 같은 사이트 fetch 로 API 를 부르므로 Strict 로도 실린다.</li>
 *   <li>{@code Max-Age} 는 Redis 에 둔 값의 수명({@code auth.oauth.*})과 같다 — 쿠키가 먼저 죽으면 아직 유효한 값을 가진 사용자가 거부된다.</li>
 *   <li>state 쿠키가 있어야 콜백이 이 브라우저가 시작한 로그인임을 확인할 수 있다(double-submit). 표 쿠키는 표 원문을 화면 스크립트 · 주소에 두지 않으려는
 *       것이다.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class OAuthCookieProvider {

    /** 쿠키 이름의 단일 기준점. 컨트롤러의 {@code @CookieValue} 도 이 상수를 쓴다. */
    public static final String OAUTH_STATE_COOKIE = "oauthState";
    public static final String OAUTH_SIGNUP_TICKET_COOKIE = "oauthSignupTicket";
    public static final String OAUTH_LINK_TICKET_COOKIE = "oauthLinkTicket";
    private static final String SAME_SITE = "Strict";

    private final OAuthLoginProperties properties;

    public ResponseCookie createState(String state) {
        return create(OAUTH_STATE_COOKIE, state, properties.stateTtl());
    }

    public ResponseCookie clearState() {
        return clear(OAUTH_STATE_COOKIE);
    }

    public ResponseCookie createSignupTicket(String ticket) {
        return create(OAUTH_SIGNUP_TICKET_COOKIE, ticket, properties.signupTicketTtl());
    }

    public ResponseCookie clearSignupTicket() {
        return clear(OAUTH_SIGNUP_TICKET_COOKIE);
    }

    public ResponseCookie createLinkTicket(String ticket) {
        return create(OAUTH_LINK_TICKET_COOKIE, ticket, properties.linkTicketTtl());
    }

    public ResponseCookie clearLinkTicket() {
        return clear(OAUTH_LINK_TICKET_COOKIE);
    }

    private static ResponseCookie create(String name, String value, Duration maxAge) {
        return base(name, value).maxAge(maxAge).build();
    }

    private static ResponseCookie clear(String name) {
        return base(name, "").maxAge(0).build();
    }

    private static ResponseCookie.ResponseCookieBuilder base(String name, String value) {
        return ResponseCookie.from(name, value)
            .httpOnly(true)
            .secure(true)
            .sameSite(SAME_SITE)
            .path(RefreshCookieProvider.AUTH_PATH);
    }
}
