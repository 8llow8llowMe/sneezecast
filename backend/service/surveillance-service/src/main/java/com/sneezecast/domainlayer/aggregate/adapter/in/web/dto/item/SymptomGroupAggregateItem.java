package com.sneezecast.domainlayer.aggregate.adapter.in.web.dto.item;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.sneezecast.common.dto.metadata.CodeNameDescriptionMetadata;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import lombok.Builder;

/**
 * 증상군 하나의 비율 · 전주 대비 변화. 동네가 자료 부족이면 이 항목 자체가 응답에 없다.
 */
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "증상군별 집계 항목 DTO")
public record SymptomGroupAggregateItem(
    @Schema(description = "증상군", example = "{\"code\":\"RESPIRATORY\",\"name\":\"호흡기\",\"description\":\"발열 · 기침 · 인후통\"}")
    CodeNameDescriptionMetadata group,

    @Schema(description = "참여자 대비 그 증상군 보고 비율 (%, 소수 첫째 자리 반올림)", example = "12.5")
    BigDecimal rate,

    @Schema(description = "전주 대비 변화 (DOWN · FLAT · SLIGHT · HIGH). 전주 집계가 없거나 전주가 자료 부족이면 없다",
        example = "{\"code\":\"SLIGHT\",\"name\":\"조금 늘었어요\",\"description\":\"전주보다 aggregate.slight-delta-pp(기본 3%p) 이상 높다\"}")
    CodeNameDescriptionMetadata trend
) {

}
