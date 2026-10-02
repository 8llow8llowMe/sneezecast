package com.sneezecast.domainlayer.auth.application.port.out;

/** 소셜 제공자의 인가(동의) 화면 주소를 만든다. 앱 키 · 콜백 주소 · 동의 항목은 구현이 설정에서 채운다. */
public interface OAuthAuthorizationUrlPort {

    /**
     * @param state         이 브라우저에 묶은 일회용 state
     * @param switchAccount true 면 제공자에 로그인된 계정을 그대로 쓰지 않고 계정을 고르게 한다 ("다른 카카오 계정으로 계속하기")
     */
    String authorizationUrl(String state, boolean switchAccount);
}
