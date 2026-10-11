package com.sneezecast.domainlayer.advisory.adapter.in.web.dto.item;

import com.sneezecast.common.dto.metadata.CodeNameDescriptionMetadata;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

/**
 * 검토 후보 동네 · 주의 안내 하나 — 상세({@code GET /api/v1/advisories/admin/{advisoryId}}) · 처리 요청에 쓸 ID · 상태 · 버전만 싣는다.
 */
@Builder
@Schema(description = "검토 후보의 안내 상태 항목 DTO")
public record AdvisoryReviewCandidateAdvisoryItem(
    @Schema(description = "안내 ID", example = "7250000000000000000")
    String advisoryId,

    @Schema(description = "상태 (DRAFT · AI_DRAFTED · EDITED · APPROVED · PUBLISHED · RETRACTED)",
        example = "{\"code\":\"EDITED\",\"name\":\"수정됨\",\"description\":\"운영자가 제목 · 본문을 고쳤다. 발행하려면 승인을 받아야 한다\"}")
    CodeNameDescriptionMetadata status,

    @Schema(description = "낙관적 락 버전 — 처리 요청에 그대로 싣는다", example = "1")
    long version
) {

}
