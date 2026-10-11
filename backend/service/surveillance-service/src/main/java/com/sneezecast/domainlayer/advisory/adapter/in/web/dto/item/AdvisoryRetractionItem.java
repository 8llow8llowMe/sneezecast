package com.sneezecast.domainlayer.advisory.adapter.in.web.dto.item;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * 철회 표시. 운영자가 발행한 안내를 내렸다는 사실과 시각만 싣는다 — 내린 안내의 제목 · 본문 · 인용 수치와 철회 사유(운영자 메모)는 싣지 않는다.
 */
@Schema(description = "안내 철회 표시 DTO")
public record AdvisoryRetractionItem(
    @Schema(description = "철회 시각 (ISO-8601 UTC, 초 단위)", example = "2026-10-08T03:00:00Z")
    Instant retractedAt
) {

}
