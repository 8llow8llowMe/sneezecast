package com.sneezecast.domainlayer.advisory.adapter.in.web.dto.request;

import com.sneezecast.domainlayer.advisory.application.exception.AdvisoryValidationMessage;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;

/**
 * 검토 후보 쿼리 파라미터 ({@code ?week=}). 생략하면 현재 주 − 1(마감될 수 있는 가장 최근 주)이다.
 */
public record AdvisoryReviewCandidateRequest(
    @Schema(description = "[선택] 집계 주 YYYY-Www. 생략하면 현재 주 − 1. 현재 주 ~ 51주 전만", example = "2026-W40")
    @Pattern(regexp = AdvisoryValidationMessage.ISO_WEEK_REGEXP, message = AdvisoryValidationMessage.ISO_WEEK_FORMAT_INVALID)
    String week
) {

}
