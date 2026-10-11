package com.sneezecast.domainlayer.advisory.adapter.in.web.dto.response;

import com.sneezecast.domainlayer.advisory.adapter.in.web.dto.item.AdvisoryHistoryItem;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 운영자가 보는 안내 한 건과 그 전 이력 (오래된 것부터).
 */
@Schema(description = "운영자 안내 상세 응답 DTO")
public record AdvisoryOperatorDetailResponse(
    @Schema(description = "안내")
    AdvisoryOperatorResponse advisory,

    @Schema(description = "생성 · 전이 · 수정 이력, 오래된 것부터")
    List<AdvisoryHistoryItem> histories
) {

}
