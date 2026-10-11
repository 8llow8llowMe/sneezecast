package com.sneezecast.domainlayer.advisory.adapter.in.web.dto.response;

import com.sneezecast.common.dto.metadata.CodeNameDescriptionMetadata;
import com.sneezecast.domainlayer.advisory.adapter.in.web.dto.item.AdvisoryOperatorCitationItem;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import lombok.Builder;

/**
 * 운영자가 보는 안내 한 건. 처리 응답(초안 생성 · 수정 · 승인 · 발행 · 철회)과 상세의 {@code advisory} 가 같은 모양이다. {@code version} 은 다음 처리
 * 요청이 실어 보낼 값이다(낙관적 락). 값이 없는 키(정정 · 발행 · 철회 시각)는 null 로 둔다.
 */
@Builder
@Schema(description = "운영자 안내 응답 DTO")
public record AdvisoryOperatorResponse(
    @Schema(description = "안내 ID", example = "7250000000000000000")
    String advisoryId,

    @Schema(description = "SGIS 행정동 코드 8자리", example = "11240660")
    String districtCode,

    @Schema(description = "근거 집계 주 (ISO 주, 월요일 시작, KST 달력 기준)", example = "2026-W40")
    String isoWeek,

    @Schema(description = "상태 (DRAFT · AI_DRAFTED · EDITED · APPROVED · PUBLISHED · RETRACTED)",
        example = "{\"code\":\"DRAFT\",\"name\":\"초안\",\"description\":\"운영자가 마감된 집계로 만든 초안\"}")
    CodeNameDescriptionMetadata status,

    @Schema(description = "제목 (현재본)", example = "가락1동 발열 · 기침 보고가 지난 4주보다 많이 늘었어요")
    String title,

    @Schema(description = "본문 (현재본)", example = "손을 자주 씻고 기침할 때는 옷소매로 가려 주세요.")
    String body,

    @Schema(description = "인용 수치 — 초안 생성 때 마감 집계에서 복사해 고정한 값")
    AdvisoryOperatorCitationItem citation,

    @Schema(description = "이 안내가 정정한 이전 안내 ID. 정정 발행(#267)이 아니면 null")
    String correctsAdvisoryId,

    @Schema(description = "발행 시각 (ISO-8601 UTC, 초 단위). 발행됨 · 철회됨에만 있다", example = "2026-10-06T01:00:00Z")
    Instant publishedAt,

    @Schema(description = "철회 시각 (ISO-8601 UTC, 초 단위). 철회됨에만 있다")
    Instant retractedAt,

    @Schema(description = "낙관적 락 버전 — 다음 처리 요청에 그대로 싣는다. 다르면 409 ADVISORY_006", example = "0")
    long version,

    @Schema(description = "초안 생성 시각 (ISO-8601 UTC, 초 단위)", example = "2026-10-06T00:30:00Z")
    Instant createdAt,

    @Schema(description = "마지막 처리 시각 (ISO-8601 UTC, 초 단위)", example = "2026-10-06T01:00:00Z")
    Instant updatedAt
) {

}
