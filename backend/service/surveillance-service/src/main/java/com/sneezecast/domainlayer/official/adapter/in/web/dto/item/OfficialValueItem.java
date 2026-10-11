package com.sneezecast.domainlayer.official.adapter.in.web.dto.item;

import com.sneezecast.common.dto.metadata.CodeNameDescriptionMetadata;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import lombok.Builder;

/**
 * 원값 그대로다 (공공누리 제4유형 — 변경금지). 전주 대비 같은 파생값을 서버가 만들지 않는다.
 */
@Builder
@Schema(description = "연령대 하나의 값")
public record OfficialValueItem(
    @Schema(description = "연령대 (OfficialAgeGroup)", example = "{\"code\":\"ALL\",\"name\":\"전체\",\"description\":\"연령 구분 없는 전체 값. 기본값\"}")
    CodeNameDescriptionMetadata ageGroup,

    @Schema(description = "값 (단위는 metric, 소수 둘째 자리까지). 원천이 빈 칸이면 null — 0 과 다르다", nullable = true, example = "152.00")
    BigDecimal value
) {

}
