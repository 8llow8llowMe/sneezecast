package com.sneezecast.domainlayer.auth.application.model;

import java.time.Instant;
import lombok.Builder;

/**
 * 로그인으로 새로 만드는 세션. <b>refresh 토큰 원문은 담지 않는다</b> — 저장소는 jti 만 비교한다.
 *
 * @param refreshTokenId 지금 유효한 refresh 토큰의 jti
 * @param deviceLabel    "OS · 브라우저" 로 줄인 기기 이름. User-Agent 원문 · IP 는 저장하지 않는다
 * @param accessToken    함께 발급한 access token — 세션을 폐기할 때 블랙리스트에 올린다
 * @param issuedAt       생성 시각. createdAt · lastUsedAt 의 초기값이다
 */
@Builder
public record NewRefreshSession(
    long memberId,
    String sessionId,
    String refreshTokenId,
    String deviceLabel,
    SessionAccessToken accessToken,
    Instant issuedAt
) {
}
