package com.sneezecast.domainlayer.auth.adapter.in.web.dto.item;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import lombok.Builder;

@Builder
@Schema(description = "로그인 기기(세션) 항목 DTO")
public record AuthSessionItem(
    @Schema(description = "세션 아이디. 기기별 로그아웃에 쓴다", example = "3f2a9c11-0e4b-4a1f-9c3d-0b8e2f7a5d61")
    String sessionId,

    @Schema(description = "기기 이름 (OS · 브라우저). 모르면 \"알 수 없는 기기\"", example = "iPhone · Safari")
    String deviceLabel,

    @Schema(description = "로그인 시각 (ISO-8601 UTC)", example = "2026-10-01T00:30:00Z")
    Instant createdAt,

    @Schema(description = "마지막 사용(로그인 · 토큰 재발급) 시각 (ISO-8601 UTC)", example = "2026-10-01T05:12:00Z")
    Instant lastUsedAt,

    @Schema(description = "지금 요청한 기기인지", example = "true")
    boolean current
) {

}
