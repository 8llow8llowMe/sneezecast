package com.sneezecast.domainlayer.auth.application.port.out.query;

import java.time.Instant;
import lombok.Builder;

/**
 * 로그인 기기 목록의 한 줄.
 *
 * @param deviceLabel 로그인 때 저장한 "OS · 브라우저" 이름
 * @param lastUsedAt  마지막 로그인 · 재발급 시각
 */
@Builder
public record RefreshSessionQueryResult(
    String sessionId,
    String deviceLabel,
    Instant createdAt,
    Instant lastUsedAt
) {
}
