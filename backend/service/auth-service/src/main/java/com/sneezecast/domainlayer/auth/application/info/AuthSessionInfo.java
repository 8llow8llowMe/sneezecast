package com.sneezecast.domainlayer.auth.application.info;

import java.time.Instant;
import lombok.Builder;

/**
 * 로그인 기기 한 줄.
 *
 * @param current 지금 요청한 access token 의 세션인지
 */
@Builder
public record AuthSessionInfo(
    String sessionId,
    String deviceLabel,
    Instant createdAt,
    Instant lastUsedAt,
    boolean current
) {
}
