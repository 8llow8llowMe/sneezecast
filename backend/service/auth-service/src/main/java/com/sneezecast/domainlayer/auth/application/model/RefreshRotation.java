package com.sneezecast.domainlayer.auth.application.model;

import java.time.Instant;
import lombok.Builder;

/**
 * refresh 회전 요청. 새 토큰은 회전 전에 미리 만들어 두고, 저장소가 원자적으로 회전에 성공했을 때만 응답에 쓴다 (실패하면 버린다).
 *
 * @param presentedTokenId 클라이언트가 낸 refresh 토큰의 jti
 * @param newTokenId       새로 발급한 refresh 토큰의 jti
 * @param newAccessToken   새로 발급한 access token
 * @param rotatedAt        회전 시각 — lastUsedAt · rotatedAt 이 된다
 */
@Builder
public record RefreshRotation(
    long memberId,
    String sessionId,
    String presentedTokenId,
    String newTokenId,
    SessionAccessToken newAccessToken,
    Instant rotatedAt
) {
}
