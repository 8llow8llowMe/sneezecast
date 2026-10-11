package com.sneezecast.domainlayer.advisory.adapter.in.web.dto.response;

import com.sneezecast.domainlayer.advisory.adapter.in.web.dto.item.AdvisoryReviewCandidateItem;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Builder;

/**
 * 한 주의 검토 후보 목록. 쪽을 나누지 않는다 — 후보는 그 주 집계 행이 있는 동네 안에서만 나온다.
 */
@Builder
@Schema(description = "검토 후보 목록 응답 DTO")
public record AdvisoryReviewCandidateListResponse(
    @Schema(description = "조회한 집계 주 (ISO 주, 월요일 시작, KST 달력 기준)", example = "2026-W40")
    String isoWeek,

    @Schema(description = "그 주 집계가 마감됐는지. 마감 전이면 어느 후보로도 초안을 만들 수 없다", example = "true")
    boolean finalized,

    @Schema(description = "지금 쓰는 참여 급증 기준 — 전주 대비 증가율 (%)", example = "30")
    int surgeChangePercent,

    @Schema(description = "지금 쓰는 반복 보고 기준 — 참여자 대비 수정된 보고 비율 (%)", example = "20")
    int repeatRevisedPercent,

    @Schema(description = "후보. 신호가 있는 후보 → 단계(많이 늘었어요 → 조금 늘었어요 → 평소 수준 → 자료 부족) → 행정동 코드 순. 없으면 빈 배열")
    List<AdvisoryReviewCandidateItem> candidates
) {

}
