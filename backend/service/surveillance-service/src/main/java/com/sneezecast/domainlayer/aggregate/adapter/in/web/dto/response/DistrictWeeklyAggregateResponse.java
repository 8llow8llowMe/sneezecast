package com.sneezecast.domainlayer.aggregate.adapter.in.web.dto.response;

import com.sneezecast.domainlayer.aggregate.adapter.in.web.dto.item.DistrictWeeklyAggregateItem;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

/**
 * 동네 하나의 한 주 공개 집계 (홈). 동네 값의 모양은 지도 목록의 항목과 같다.
 */
@Builder
@Schema(description = "행정동 주간 집계 응답 DTO")
public record DistrictWeeklyAggregateResponse(
    @Schema(description = "집계 주 (ISO 주, 월요일 시작, KST 달력 기준)", example = "2026-W41")
    String isoWeek,

    @Schema(description = "그 주가 마감됐는지. false 면 진행 중인 주이거나 아직 마감 전이라 값이 바뀔 수 있다", example = "false")
    boolean finalized,

    @Schema(description = "수치를 공개하는 최소 참여자 수", example = "100")
    int publicThreshold,

    @Schema(description = "조금 늘었어요 하한 (%p) — 단계(기준선 대비)와 증상군 변화(전주 대비)가 같은 값을 쓴다", example = "3")
    int slightDeltaPp,

    @Schema(description = "많이 늘었어요 하한 (%p)", example = "8")
    int highDeltaPp,

    @Schema(description = "행정동 집계")
    DistrictWeeklyAggregateItem district
) {

}
