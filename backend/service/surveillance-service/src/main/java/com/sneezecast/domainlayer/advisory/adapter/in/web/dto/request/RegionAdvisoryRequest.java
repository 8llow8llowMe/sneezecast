package com.sneezecast.domainlayer.advisory.adapter.in.web.dto.request;

import com.sneezecast.domainlayer.advisory.application.exception.AdvisoryValidationMessage;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * 동네 안내 쿼리 파라미터 ({@code ?districtCode=&week=}). 둘 다 필수다 — 안내는 마감된 주에만 있어 "현재 주" 기본값이 늘 안내 없음이 되고, 화면 주소
 * ({@code /notice/[region]/[week]})가 늘 주를 갖는다.
 */
public record RegionAdvisoryRequest(
    @Schema(description = "[필수] SGIS 행정동 코드 숫자 8자리", example = "11240660", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = AdvisoryValidationMessage.DISTRICT_CODE_REQUIRED)
    @Pattern(regexp = AdvisoryValidationMessage.DISTRICT_CODE_REGEXP, message = AdvisoryValidationMessage.DISTRICT_CODE_FORMAT_INVALID)
    String districtCode,

    @Schema(description = "[필수] 근거 집계 주 YYYY-Www. 현재 주 ~ 51주 전만", example = "2026-W40", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = AdvisoryValidationMessage.ISO_WEEK_REQUIRED)
    @Pattern(regexp = AdvisoryValidationMessage.ISO_WEEK_REGEXP, message = AdvisoryValidationMessage.ISO_WEEK_FORMAT_INVALID)
    String week
) {

}
