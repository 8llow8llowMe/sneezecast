package com.sneezecast.domainlayer.advisory.adapter.in.web.dto.item;

import com.sneezecast.common.dto.metadata.CodeNameDescriptionMetadata;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import lombok.Builder;

/**
 * 안내가 인용한 증상군 하나의 보고 수 · 비율 (발행 시점 고정).
 */
@Builder
@Schema(description = "안내 인용 증상군 항목 DTO")
public record AdvisoryCitedGroupItem(
    @Schema(description = "증상군", example = "{\"code\":\"RESPIRATORY\",\"name\":\"호흡기\",\"description\":\"발열 · 기침 · 인후통\"}")
    CodeNameDescriptionMetadata group,

    @Schema(description = "인용 보고 수", example = "20")
    int count,

    @Schema(description = "인용 참여자 대비 그 증상군 보고 비율 (%, 소수 첫째 자리 반올림)", example = "13.3")
    BigDecimal rate
) {

}
