package com.sneezecast.domainlayer.advisory.adapter.in.web.dto.item;

import com.sneezecast.common.dto.metadata.CodeNameDescriptionMetadata;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

/**
 * 운영자가 보는 안내의 인용 수치 (advisory {@code cited_*}). 초안을 만들 때 마감 집계에서 복사해 고정한 <b>수</b>만 싣는다 — 비율은 공개 안내 · 공개 집계가
 * 같은 수로 계산해 보인다. 규칙 버전은 운영자가 어떤 판정 규칙의 집계를 인용했는지 확인하는 값이다(공개 응답에는 없다).
 */
@Builder
@Schema(description = "운영자 안내 인용 수치 DTO")
public record AdvisoryOperatorCitationItem(
    @Schema(description = "인용 단계 (NORMAL · SLIGHT · HIGH). 자료 부족 집계로는 안내를 만들지 않으므로 INSUFFICIENT 는 없다",
        example = "{\"code\":\"HIGH\",\"name\":\"많이 늘었어요\",\"description\":\"증상 보고 비율이 기준선보다 aggregate.high-delta-pp(기본 8%p) 이상 높다\"}")
    CodeNameDescriptionMetadata level,

    @Schema(description = "인용 참여자 수 (증상 없음 보고 포함 — 비율의 분모)", example = "150")
    int participantCount,

    @Schema(description = "인용 증상 보고 수 (증상군을 하나 이상 보고한 수)", example = "30")
    int symptomaticCount,

    @Schema(description = "인용 호흡기 증상군 보고 수", example = "20")
    int respiratoryCount,

    @Schema(description = "인용 장관 증상군 보고 수", example = "15")
    int entericCount,

    @Schema(description = "인용 집계의 판정 규칙 버전", example = "2026-10-08")
    String ruleVersion
) {

}
