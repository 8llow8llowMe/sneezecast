package com.sneezecast.domainlayer.aggregate.adapter.in.web.dto.item;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.sneezecast.common.dto.metadata.CodeNameDescriptionMetadata;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import lombok.Builder;

/**
 * 행정동 하나의 한 주 공개 집계. <b>null 인 항목은 응답에서 뺀다</b> — 자료 부족(INSUFFICIENT)이면 {@code symptomaticCount} · {@code symptomRate} ·
 * {@code baselineRate} · {@code groups} 가 아예 없다. 화면은 값이 없으면 수치 · 상태색을 그리지 않는다.
 */
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "행정동 주간 집계 항목 DTO")
public record DistrictWeeklyAggregateItem(
    @Schema(description = "SGIS 행정동 코드 8자리", example = "11440660")
    String code,

    @Schema(description = "행정동 이름", example = "서교동")
    String name,

    @Schema(description = "시도 · 시군구 표기", example = "서울특별시 마포구")
    String sigungu,

    @Schema(description = "현행 행정동인지. 폐지된 코드면 false", example = "true")
    boolean active,

    @Schema(description = "단계 (NORMAL · SLIGHT · HIGH · INSUFFICIENT). 집계 행이 없는 동은 INSUFFICIENT",
        example = "{\"code\":\"SLIGHT\",\"name\":\"조금 늘었어요\",\"description\":\"증상 보고 비율이 기준선보다 aggregate.slight-delta-pp(기본 3%p) 이상 높다\"}")
    CodeNameDescriptionMetadata level,

    @Schema(description = "자료 부족 이유 (LOW_SAMPLE · UNSTABLE · NO_BASELINE). INSUFFICIENT 일 때만 있다 — 화면은 셋 모두 자료 부족으로 그린다",
        example = "{\"code\":\"LOW_SAMPLE\",\"name\":\"참여 부족\",\"description\":\"그 주 참여자가 최소 표본(aggregate.min-sample, 기본 100명)보다 적다\"}")
    CodeNameDescriptionMetadata insufficientReason,

    @Schema(description = "그 주 참여자 수 (증상 없음 보고 포함 — 비율의 분모). 자료 부족이어도 있다. 집계 행이 없으면 0", example = "128")
    int participantCount,

    @Schema(description = "마지막 재계산 시각 (ISO-8601 UTC, 초 단위). 집계 행이 없으면 없다", example = "2026-10-08T00:10:00Z")
    Instant calculatedAt,

    @Schema(description = "증상군을 하나 이상 보고한 수. 자료 부족이면 없다", example = "20")
    Integer symptomaticCount,

    @Schema(description = "참여자 대비 증상 보고 비율 (%, 소수 첫째 자리 반올림). 자료 부족이면 없다", example = "15.6")
    BigDecimal symptomRate,

    @Schema(description = "판정에 쓴 기준선 비율 (%, 소수 첫째 자리 반올림 — 직전 마감 주 4개의 합산 비율). 자료 부족이면 없다", example = "10.0")
    BigDecimal baselineRate,

    @Schema(description = "증상군별 비율 · 전주 대비 변화 (호흡기 → 장관). 자료 부족이면 없다")
    List<SymptomGroupAggregateItem> groups
) {

}
