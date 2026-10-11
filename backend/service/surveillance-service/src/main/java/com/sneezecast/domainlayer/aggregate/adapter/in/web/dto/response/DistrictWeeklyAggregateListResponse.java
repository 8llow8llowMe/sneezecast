package com.sneezecast.domainlayer.aggregate.adapter.in.web.dto.response;

import com.sneezecast.domainlayer.aggregate.adapter.in.web.dto.item.DistrictWeeklyAggregateItem;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Builder;

/**
 * 시군구 하나의 행정동별 한 주 공개 집계 (지도). 요청한(찾은) 동네는 시군구 밖 · 집계 행 없음이어도 {@code districts} 에 꼭 있다.
 */
@Builder
@Schema(description = "시군구 행정동별 주간 집계 응답 DTO")
public record DistrictWeeklyAggregateListResponse(
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

    @Schema(description = "행정동별 집계. include 동네가 시군구 목록에 없으면 맨 앞, 이어서 시군구의 현행 동 코드 오름차순. 시군구에 현행 동이 없으면 빈 배열")
    List<DistrictWeeklyAggregateItem> districts
) {

}
