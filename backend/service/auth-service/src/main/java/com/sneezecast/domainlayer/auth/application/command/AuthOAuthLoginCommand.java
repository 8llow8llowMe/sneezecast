package com.sneezecast.domainlayer.auth.application.command;

import com.sneezecast.domainlayer.member.domain.enums.OAuthProvider;
import lombok.Builder;

/**
 * 소셜 로그인 콜백 명령.
 *
 * @param code        제공자가 콜백 주소로 준 인가 코드 (1회용)
 * @param state       콜백 주소로 돌아온 state
 * @param cookieState 인가 때 이 브라우저에 심은 state 쿠키. 없으면 null
 * @param deviceLabel User-Agent 를 "OS · 브라우저" 로 줄인 기기 이름
 */
@Builder
public record AuthOAuthLoginCommand(
    OAuthProvider provider,
    String code,
    String state,
    String cookieState,
    String deviceLabel
) {

    /** 인가 코드 · state 를 로그 · 예외 메시지에 흘리지 않는다. */
    @Override
    public String toString() {
        return "AuthOAuthLoginCommand[provider=" + provider + ", code=****, state=****, cookieState=****, deviceLabel=" + deviceLabel + "]";
    }
}
