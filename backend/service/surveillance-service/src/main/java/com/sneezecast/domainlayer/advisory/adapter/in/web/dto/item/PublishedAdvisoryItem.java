package com.sneezecast.domainlayer.advisory.adapter.in.web.dto.item;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import lombok.Builder;

/**
 * 발행 중인 안내 하나. 운영자 · 이력 내부 정보(안내 ID, 운영자, 버전, AI 모델, 메모)는 싣지 않는다.
 */
@Builder
@Schema(description = "발행 중인 동네 안내 DTO")
public record PublishedAdvisoryItem(
    @Schema(description = "제목", example = "가락1동 발열 · 기침 보고가 지난 4주보다 많이 늘었어요")
    String title,

    @Schema(description = "본문 (운영자가 검토 · 승인한 문장)", example = "손을 자주 씻고 기침할 때는 옷소매로 가려 주세요.")
    String body,

    @Schema(description = "발행 시각 (ISO-8601 UTC, 초 단위)", example = "2026-10-06T01:00:00Z")
    Instant publishedAt,

    @Schema(description = "인용 수치 (발행 시점 고정)")
    AdvisoryCitationItem citation,

    @Schema(description = "정정 이력 — 정정 시각만, 최근 것부터. 정정이 없으면 빈 배열. 정정 사유는 싣지 않는다")
    List<AdvisoryCorrectionItem> corrections
) {

}
