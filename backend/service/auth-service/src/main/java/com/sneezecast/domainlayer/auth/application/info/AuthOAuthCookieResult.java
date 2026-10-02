package com.sneezecast.domainlayer.auth.application.info;

/**
 * 소셜 로그인 응답 본문과 함께 내릴 쿠키 하나. 쿠키를 굽는 일(이름 · 속성 · 경로)은 web 어댑터의 몫이라, 유스케이스는 종류와 값만 넘긴다.
 *
 * @param cookie      내릴 쿠키 종류
 * @param cookieValue 쿠키 값 원문 (state · refresh 토큰 · 표)
 */
public record AuthOAuthCookieResult<T>(T response, Cookie cookie, String cookieValue) {

    public enum Cookie {
        STATE, REFRESH_TOKEN, SIGNUP_TICKET, LINK_TICKET
    }

    public static <T> AuthOAuthCookieResult<T> of(T response, Cookie cookie, String cookieValue) {
        return new AuthOAuthCookieResult<>(response, cookie, cookieValue);
    }

    /** 쿠키 값 원문은 로그 · 예외 메시지에 흘리지 않는다. 응답 본문은 그 타입의 toString 에 맡긴다. */
    @Override
    public String toString() {
        return "AuthOAuthCookieResult[response=" + response + ", cookie=" + cookie + ", cookieValue=****]";
    }
}
