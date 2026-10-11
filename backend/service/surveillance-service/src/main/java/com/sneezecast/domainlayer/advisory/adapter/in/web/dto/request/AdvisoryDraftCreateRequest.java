package com.sneezecast.domainlayer.advisory.adapter.in.web.dto.request;

import com.sneezecast.domainlayer.advisory.application.exception.AdvisoryValidationMessage;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Builder;

/**
 * 초안 생성 본문. 근거 집계는 동네 · 주로 찾는다(검토 후보가 동네 · 주 단위다). 인용 수치는 받지 않는다 — 서버가 마감된 집계에서 복사한다.
 */
@Builder
public record AdvisoryDraftCreateRequest(
    @Schema(description = "[필수] 대상 행정동 코드 (SGIS 숫자 8자리)", example = "11240660", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = AdvisoryValidationMessage.DISTRICT_CODE_REQUIRED)
    @Pattern(regexp = AdvisoryValidationMessage.DISTRICT_CODE_REGEXP, message = AdvisoryValidationMessage.DISTRICT_CODE_FORMAT_INVALID)
    String districtCode,

    @Schema(description = "[필수] 근거 집계 주 YYYY-Www (마감된 주)", example = "2026-W40", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = AdvisoryValidationMessage.ISO_WEEK_REQUIRED)
    @Pattern(regexp = AdvisoryValidationMessage.ISO_WEEK_REGEXP, message = AdvisoryValidationMessage.ISO_WEEK_FORMAT_INVALID)
    String isoWeek,

    @Schema(description = "[필수] 제목. 공백만은 안 되고 100자 이하", example = "가락1동 발열 · 기침 보고가 지난 4주보다 많이 늘었어요",
        requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = AdvisoryValidationMessage.TITLE_REQUIRED)
    @Pattern(regexp = AdvisoryValidationMessage.HAS_VISIBLE_CHARACTER_REGEXP, message = AdvisoryValidationMessage.TITLE_REQUIRED)
    @Size(max = AdvisoryValidationMessage.TITLE_MAX_LENGTH, message = AdvisoryValidationMessage.TITLE_TOO_LONG)
    String title,

    @Schema(description = "[필수] 본문. 공백만은 안 되고 16,383자 이하", example = "손을 자주 씻고 기침할 때는 옷소매로 가려 주세요.",
        requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = AdvisoryValidationMessage.BODY_REQUIRED)
    @Pattern(regexp = AdvisoryValidationMessage.HAS_VISIBLE_CHARACTER_REGEXP, message = AdvisoryValidationMessage.BODY_REQUIRED)
    @Size(max = AdvisoryValidationMessage.BODY_MAX_LENGTH, message = AdvisoryValidationMessage.BODY_TOO_LONG)
    String body
) {

}
