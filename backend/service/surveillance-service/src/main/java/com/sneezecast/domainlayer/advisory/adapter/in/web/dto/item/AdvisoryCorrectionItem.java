package com.sneezecast.domainlayer.advisory.adapter.in.web.dto.item;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * 정정 하나 — 이 안내(또는 사슬의 앞 안내)가 이전 안내를 철회하고 새로 발행한 시각. 정정 사유(운영자 메모)는 싣지 않는다.
 */
@Schema(description = "안내 정정 항목 DTO")
public record AdvisoryCorrectionItem(
    @Schema(description = "정정 시각 — 정정한 안내의 발행 시각 (ISO-8601 UTC, 초 단위)", example = "2026-10-07T01:00:00Z")
    Instant correctedAt
) {

}
