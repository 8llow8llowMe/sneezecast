package com.sneezecast.domainlayer.auth.application.command;

import lombok.Builder;

/**
 * 이메일 로그인 명령. 이메일 정규화는 Facade 가 한다.
 *
 * @param clientIp    IP 실패 상한의 키로만 쓴다. 저장하지 않는다
 * @param deviceLabel User-Agent 를 "OS · 브라우저" 로 줄인 기기 이름. User-Agent 원문은 넘기지 않는다
 */
@Builder
public record AuthGeneralLoginCommand(
    String email,
    String password,
    String clientIp,
    String deviceLabel
) {

    /** 비밀번호를 로그 · 예외 메시지에 흘리지 않는다. */
    @Override
    public String toString() {
        return "AuthGeneralLoginCommand[email=****, password=****, clientIp=****, deviceLabel=" + deviceLabel + "]";
    }
}
