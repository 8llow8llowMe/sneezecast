package com.sneezecast.domainlayer.aggregate.adapter.in.web.dto.item;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * 동네의 발행 중인 안내 요약 (#215) — 요청 주의 안내, 없으면 그 직전 주의 안내. 둘 다 없거나 철회됐으면 이 항목 자체가 응답에 없다. 본문 · 인용 수치 ·
 * 정정 이력은 동네 안내 API ({@code GET /api/v1/advisories?districtCode=&week=})가 이 항목의 {@code isoWeek} 로 준다.
 */
@Schema(description = "발행 중인 동네 안내 요약 DTO")
public record PublishedAdvisorySummaryItem(
    @Schema(description = "안내의 근거 집계 주 (ISO 주) — 요청 주이거나 그 직전 주. 동네 안내 화면 주소의 주", example = "2026-W41")
    String isoWeek,

    @Schema(description = "안내 제목", example = "서교동 발열 · 기침 보고가 지난 4주보다 많이 늘었어요")
    String title,

    @Schema(description = "발행 시각 (ISO-8601 UTC, 초 단위)", example = "2026-10-13T01:00:00Z")
    Instant publishedAt
) {

}
