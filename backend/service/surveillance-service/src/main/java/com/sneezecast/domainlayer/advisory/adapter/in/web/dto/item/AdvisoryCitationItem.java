package com.sneezecast.domainlayer.advisory.adapter.in.web.dto.item;

import com.sneezecast.common.dto.metadata.CodeNameDescriptionMetadata;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;
import lombok.Builder;

/**
 * 안내가 인용한 집계 수치. 운영자가 검토할 때 마감 집계에서 복사해 고정한 값(advisory {@code cited_*})이라 집계가 다시 계산돼도 바뀌지 않는다. 기준선 ·
 * 전주 대비 변화는 인용값이 아니다 — 같은 주 공개 집계에서 읽는다.
 */
@Builder
@Schema(description = "안내 인용 수치 DTO")
public record AdvisoryCitationItem(
    @Schema(description = "인용 단계 (NORMAL · SLIGHT · HIGH). 자료 부족 집계로는 안내를 만들지 않으므로 INSUFFICIENT 는 없다",
        example = "{\"code\":\"HIGH\",\"name\":\"많이 늘었어요\",\"description\":\"증상 보고 비율이 기준선보다 aggregate.high-delta-pp(기본 8%p) 이상 높다\"}")
    CodeNameDescriptionMetadata level,

    @Schema(description = "인용 참여자 수 (증상 없음 보고 포함 — 비율의 분모)", example = "150")
    int participantCount,

    @Schema(description = "인용 증상 보고 수 (증상군을 하나 이상 보고한 수)", example = "30")
    int symptomaticCount,

    @Schema(description = "인용 참여자 대비 증상 보고 비율 (%, 소수 첫째 자리 반올림)", example = "20.0")
    BigDecimal symptomRate,

    @Schema(description = "증상군별 인용 수 · 비율 (호흡기 → 장관)")
    List<AdvisoryCitedGroupItem> groups
) {

}
