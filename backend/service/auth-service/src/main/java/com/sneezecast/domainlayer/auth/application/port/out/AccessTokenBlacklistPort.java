package com.sneezecast.domainlayer.auth.application.port.out;

import java.time.Duration;

/**
 * access token 폐기 등록. 로그아웃 · 세션 폐기 · 동의 철회처럼 만료 전에 토큰을 무효화해야 하는 흐름이 쓴다.
 *
 * <p>폐기 여부 조회는 이 포트가 아니라 security-core 의 {@code AccessTokenBlacklistVerifier} 로 필터가 한다.
 */
public interface AccessTokenBlacklistPort {

    /**
     * 토큰(jti)을 남은 유효 시간 동안만 폐기 목록에 올린다. 만료된 토큰은 서명 검증에서 이미 거부되므로 더 오래 둘 이유가 없다.
     *
     * @param tokenId           access token 의 jti
     * @param remainingValidity 토큰 만료까지 남은 시간. 0 이하면 이미 만료된 토큰이라 아무것도 하지 않는다
     */
    void revoke(String tokenId, Duration remainingValidity);
}
