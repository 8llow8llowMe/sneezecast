package com.sneezecast.domainlayer.advisory.adapter.in.web.dto.item;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.sneezecast.common.dto.metadata.CodeNameDescriptionMetadata;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;
import lombok.Builder;

/**
 * 검토 후보 동네 하나. <b>자료 부족이면 증상 수 · 비율 · 기준선 · 증상군 키가 없다</b>({@code @JsonInclude(NON_NULL)} — 0 · null 로도 싣지 않는다).
 * 그 밖의 값이 없는 키(이름 · 이유 · 전주 참여 · 안내)는 null 로 둔다(운영자 API 관례).
 */
@Builder
@Schema(description = "검토 후보 항목 DTO")
public record AdvisoryReviewCandidateItem(
    @Schema(description = "SGIS 행정동 코드 8자리", example = "11240660")
    String districtCode,

    @Schema(description = "행정동 이름. 행정동 행이 없으면 null", example = "가락1동")
    String districtName,

    @Schema(description = "시도 · 시군구 표기. 행정동 행이 없으면 null", example = "서울특별시 송파구")
    String sigungu,

    @Schema(description = "현행 행정동이면 true, 폐지된 코드면 false", example = "true")
    boolean active,

    @Schema(description = "신호 (BASELINE 기준선 대비 변화 · SURGE 참여 급증 · REPEAT 반복 보고 의심), 이 순서. 신호 없이 그 주에 안내가 있어 후보가 되면 빈 배열")
    List<CodeNameDescriptionMetadata> signals,

    @Schema(description = "집계 단계 (NORMAL · SLIGHT · HIGH · INSUFFICIENT)", example = "{\"code\":\"HIGH\",\"name\":\"많이 늘었어요\",\"description\":\"…\"}")
    CodeNameDescriptionMetadata level,

    @Schema(description = "자료 부족 이유 (LOW_SAMPLE · UNSTABLE · NO_BASELINE). 자료 부족이 아니면 null")
    CodeNameDescriptionMetadata insufficientReason,

    @Schema(description = "이 동네 · 주로 초안을 만들 수 있는지 — 마감됐고 자료 부족이 아니다(아니면 초안 생성이 ADVISORY_003 · 004)", example = "true")
    boolean draftable,

    @Schema(description = "참여자 수", example = "150")
    int participantCount,

    @Schema(description = "전주 참여자 수. 전주 집계 행이 없으면 null", example = "110")
    Integer previousParticipantCount,

    @Schema(description = "그 주에 한 번 이상 수정된 보고 수 (반복 보고 신호의 근거)", example = "12")
    int revisedReportCount,

    @Schema(description = "증상군 하나 이상 보고 수. 자료 부족이면 키 없음", example = "30")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    Integer symptomaticCount,

    @Schema(description = "참여자 대비 증상 보고 비율 (%, 소수 첫째 자리 반올림). 자료 부족이면 키 없음", example = "20.0")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    BigDecimal symptomRate,

    @Schema(description = "판정에 쓴 기준선 비율 (%). 자료 부족이면 키 없음", example = "10.0")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    BigDecimal baselineRate,

    @Schema(description = "기준선 대비 변화 (%p) = symptomRate − baselineRate. 자료 부족이면 키 없음", example = "10.0")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    BigDecimal baselineDeltaPp,

    @Schema(description = "증상군별 보고 수 · 비율 (호흡기 → 장관). 자료 부족이면 키 없음")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    List<AdvisoryReviewCandidateGroupItem> groups,

    @Schema(description = "그 동네 · 주의 안내 — 발행 중 > 검토 중(가장 최근) > 가장 최근 철회 순으로 하나. 없으면 null")
    AdvisoryReviewCandidateAdvisoryItem advisory
) {

}
