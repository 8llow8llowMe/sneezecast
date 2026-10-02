package com.sneezecast.domainlayer.report.adapter.in.web.dto.response;

import com.sneezecast.common.dto.metadata.CodeNameDescriptionMetadata;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import lombok.Builder;

/**
 * 본인의 이번 주 보고. 보고 ID · 보고자 키 · 수정 횟수는 싣지 않는다.
 */
@Builder
@Schema(description = "이번 주 보고 응답 DTO")
public record WeeklyReportResponse(
    @Schema(description = "보고 주 (ISO 주, 월요일 시작, KST 달력 기준). 서버가 정한다", example = "2026-W40")
    String isoWeek,

    @Schema(description = "보고 행정동 코드 (SGIS 8자리). 같은 주에 고쳤으면 마지막 값", example = "11230510")
    String districtCode,

    @Schema(description = "보고한 증상군 (선언 순서: 호흡기 → 장관). 빈 배열이면 증상 없음",
        example = "[{\"code\":\"RESPIRATORY\",\"name\":\"호흡기\",\"description\":\"발열 · 기침 · 인후통\"}]")
    List<CodeNameDescriptionMetadata> symptomGroups,

    @Schema(description = "이번 주 첫 보고 시각 (ISO-8601 UTC, 초 단위)", example = "2026-10-01T05:12:00Z")
    Instant reportedAt,

    @Schema(description = "마지막 수정 시각 (ISO-8601 UTC, 초 단위). 고친 적이 없으면 reportedAt 과 같다", example = "2026-10-01T05:12:00Z")
    Instant updatedAt
) {

    /** 증상(민감정보)을 로그에 흘리지 않는다. */
    @Override
    public String toString() {
        return "WeeklyReportResponse[isoWeek=" + isoWeek + ", districtCode=" + districtCode + ", symptomGroups=****]";
    }
}
