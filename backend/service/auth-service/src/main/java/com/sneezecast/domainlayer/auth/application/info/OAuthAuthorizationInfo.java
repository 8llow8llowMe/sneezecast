package com.sneezecast.domainlayer.auth.application.info;

/**
 * 카카오 인가 주소와 그 주소에 실은 state. state 는 web 어댑터가 쿠키로도 심는다 — 쿠키에 묶이지 않은 state 는 이 브라우저가 시작한 요청이라는 근거가 없다.
 */
public record OAuthAuthorizationInfo(String authorizeUrl, String state) {

    /** state 원문은 로그 · 예외 메시지에 흘리지 않는다. 주소에도 state 가 실려 있어 함께 가린다. */
    @Override
    public String toString() {
        return "OAuthAuthorizationInfo[authorizeUrl=****, state=****]";
    }
}
