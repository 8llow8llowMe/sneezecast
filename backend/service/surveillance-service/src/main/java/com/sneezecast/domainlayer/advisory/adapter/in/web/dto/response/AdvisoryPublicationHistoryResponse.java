package com.sneezecast.domainlayer.advisory.adapter.in.web.dto.response;

import com.sneezecast.domainlayer.advisory.adapter.in.web.dto.item.AdvisoryPublicationHistoryItem;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Builder;

/**
 * 운영자 발행 이력(#267, A03) — {@code fromIsoWeek} ~ {@code toIsoWeek}(4주, 양 끝 포함)의 발행됨 · 철회됨 안내, 발행 시각 최근 것부터. 쪽 나누기 없음.
 */
@Builder
@Schema(description = "운영자 발행 이력 응답 DTO")
public record AdvisoryPublicationHistoryResponse(
    @Schema(description = "범위의 첫 주 (끝 주 − 3주)", example = "2026-W37")
    String fromIsoWeek,

    @Schema(description = "범위의 끝 주 (요청한 주, 생략하면 현재 주 − 1)", example = "2026-W40")
    String toIsoWeek,

    @Schema(description = "발행됨 · 철회됨 안내, 발행 시각 최근 것부터 (같으면 나중에 만든 안내부터)")
    List<AdvisoryPublicationHistoryItem> entries
) {

}
