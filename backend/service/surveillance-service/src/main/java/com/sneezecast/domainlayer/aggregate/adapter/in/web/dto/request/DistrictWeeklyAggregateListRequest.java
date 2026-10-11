package com.sneezecast.domainlayer.aggregate.adapter.in.web.dto.request;

import com.sneezecast.domainlayer.aggregate.application.exception.AggregateValidationMessage;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * 지도 집계 쿼리 파라미터 ({@code ?sigunguCode=&include=&week=}).
 */
public record DistrictWeeklyAggregateListRequest(
    @Schema(description = "[필수] SGIS 시군구 코드 숫자 5자리 (행정동 코드 앞 5자리)", example = "11440", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = AggregateValidationMessage.SIGUNGU_CODE_REQUIRED)
    @Pattern(regexp = AggregateValidationMessage.SIGUNGU_CODE_REGEXP, message = AggregateValidationMessage.SIGUNGU_CODE_FORMAT_INVALID)
    String sigunguCode,

    @Schema(description = "[선택] 꼭 담을 행정동 코드 (찾은 동네, SGIS 숫자 8자리). 시군구 밖이거나 폐지된 코드여도 담는다", example = "11440660")
    @Pattern(regexp = AggregateValidationMessage.DISTRICT_CODE_REGEXP, message = AggregateValidationMessage.DISTRICT_CODE_FORMAT_INVALID)
    String include,

    @Schema(description = "[선택] 집계 주 YYYY-Www. 생략하면 현재 주(KST). 현재 주 ~ 51주 전만", example = "2026-W41")
    @Pattern(regexp = AggregateValidationMessage.ISO_WEEK_REGEXP, message = AggregateValidationMessage.ISO_WEEK_FORMAT_INVALID)
    String week
) {

}
