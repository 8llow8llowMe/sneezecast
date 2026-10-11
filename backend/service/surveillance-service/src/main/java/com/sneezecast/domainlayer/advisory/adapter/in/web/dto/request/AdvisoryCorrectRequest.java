package com.sneezecast.domainlayer.advisory.adapter.in.web.dto.request;

import com.sneezecast.domainlayer.advisory.application.exception.AdvisoryValidationMessage;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Builder;

/**
 * 정정 발행 본문(#267). 제목 · 본문은 새 안내의 전체 값이다(수정 요청과 같은 조건 · 코드). 사유는 철회 사유와 같은 조건 · 코드이고 운영자에게만 보인다.
 */
@Builder
public record AdvisoryCorrectRequest(
    @Schema(description = "[필수] 새 안내의 제목. 공백만은 안 되고 100자 이하", example = "가락1동 발열 · 기침 보고가 늘었어요", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = AdvisoryValidationMessage.TITLE_REQUIRED)
    @Pattern(regexp = AdvisoryValidationMessage.HAS_VISIBLE_CHARACTER_REGEXP, message = AdvisoryValidationMessage.TITLE_REQUIRED)
    @Size(max = AdvisoryValidationMessage.TITLE_MAX_LENGTH, message = AdvisoryValidationMessage.TITLE_TOO_LONG)
    String title,

    @Schema(description = "[필수] 새 안내의 본문(고친 본문). 공백만은 안 되고 16,383자 이하", example = "손을 자주 씻어 주세요.", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = AdvisoryValidationMessage.BODY_REQUIRED)
    @Pattern(regexp = AdvisoryValidationMessage.HAS_VISIBLE_CHARACTER_REGEXP, message = AdvisoryValidationMessage.BODY_REQUIRED)
    @Size(max = AdvisoryValidationMessage.BODY_MAX_LENGTH, message = AdvisoryValidationMessage.BODY_TOO_LONG)
    String body,

    @Schema(description = "[필수] 정정 사유. 공백만은 안 되고 500자 이하. 이력에 남고 운영자에게만 보인다 — 사용자 화면에는 보이지 않는다", example = "참여자 수 표기 오류",
        requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = AdvisoryValidationMessage.REASON_REQUIRED)
    @Pattern(regexp = AdvisoryValidationMessage.HAS_VISIBLE_CHARACTER_REGEXP, message = AdvisoryValidationMessage.REASON_REQUIRED)
    @Size(max = AdvisoryValidationMessage.MEMO_MAX_LENGTH, message = AdvisoryValidationMessage.REASON_TOO_LONG)
    String reason,

    @Schema(description = "[필수] 화면이 읽은 정정 대상(발행 중인 안내)의 버전. 지금 버전과 다르면 409 ADVISORY_006", example = "3",
        requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = AdvisoryValidationMessage.VERSION_REQUIRED)
    @PositiveOrZero(message = AdvisoryValidationMessage.VERSION_INVALID)
    Long version
) {

}
