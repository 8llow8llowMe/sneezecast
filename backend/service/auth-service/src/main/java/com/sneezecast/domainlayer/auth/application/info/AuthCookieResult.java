package com.sneezecast.domainlayer.auth.application.info;

/**
 * 응답 본문과 refresh 쿠키 값을 함께 돌려준다. 쿠키를 굽는 일(속성 · 경로)은 web 어댑터의 몫이라, 유스케이스는 값만 넘긴다.
 */
public record AuthCookieResult<T>(T response, String refreshToken) {

    public static <T> AuthCookieResult<T> of(T response, String refreshToken) {
        return new AuthCookieResult<>(response, refreshToken);
    }

    /** refresh 토큰 원문은 로그 · 예외 메시지에 흘리지 않는다. 응답 본문은 그 타입의 toString 에 맡긴다. */
    @Override
    public String toString() {
        return "AuthCookieResult[response=" + response + ", refreshToken=****]";
    }
}
