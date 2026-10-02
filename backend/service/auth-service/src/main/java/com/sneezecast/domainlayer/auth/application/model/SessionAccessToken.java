package com.sneezecast.domainlayer.auth.application.model;

import java.time.Instant;

/**
 * 세션이 마지막으로 발급받은 access token 의 jti 와 만료 시각. 세션을 폐기할 때 이 토큰을 남은 시간만큼 블랙리스트에 올려 그 기기를 바로 끊는다
 * (refresh 를 지워도 이미 받은 access 는 만료까지 통하기 때문이다).
 */
public record SessionAccessToken(String tokenId, Instant expiresAt) {
}
