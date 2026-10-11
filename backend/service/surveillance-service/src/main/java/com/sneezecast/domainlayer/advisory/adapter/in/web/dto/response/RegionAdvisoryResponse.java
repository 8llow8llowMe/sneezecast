package com.sneezecast.domainlayer.advisory.adapter.in.web.dto.response;

import com.sneezecast.domainlayer.advisory.adapter.in.web.dto.item.AdvisoryRetractionItem;
import com.sneezecast.domainlayer.advisory.adapter.in.web.dto.item.PublishedAdvisoryItem;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

/**
 * 동네 하나 · 주 하나의 공개 안내. {@code advisory}(발행 중) 와 {@code retraction}(철회 표시) 중 <b>하나만</b> 값이 있고 다른 하나는 null 이다(키는 늘
 * 있다). 안내가 없으면 이 응답 대신 {@code dataBody: null} 이다.
 */
@Builder
@Schema(description = "동네 안내 응답 DTO")
public record RegionAdvisoryResponse(
    @Schema(description = "SGIS 행정동 코드 8자리", example = "11240660")
    String districtCode,

    @Schema(description = "근거 집계 주 (ISO 주, 월요일 시작, KST 달력 기준)", example = "2026-W40")
    String isoWeek,

    @Schema(description = "발행 중인 안내. 철회 표시면 null")
    PublishedAdvisoryItem advisory,

    @Schema(description = "철회 표시. 발행 중인 안내가 있으면 null")
    AdvisoryRetractionItem retraction
) {

}
