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
 * 제목 · 본문 수정 본문 → EDITED. 제목 · 본문은 둘 다 고친 뒤의 전체 값이다(부분 수정이 아니다). 승인된 안내를 고치면 다시 승인받아야 한다.
 */
@Builder
public record AdvisoryEditRequest(
    @Schema(description = "[필수] 고친 제목. 공백만은 안 되고 100자 이하", example = "가락1동 발열 · 기침 보고가 늘었어요", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = AdvisoryValidationMessage.TITLE_REQUIRED)
    @Pattern(regexp = AdvisoryValidationMessage.HAS_VISIBLE_CHARACTER_REGEXP, message = AdvisoryValidationMessage.TITLE_REQUIRED)
    @Size(max = AdvisoryValidationMessage.TITLE_MAX_LENGTH, message = AdvisoryValidationMessage.TITLE_TOO_LONG)
    String title,

    @Schema(description = "[필수] 고친 본문. 공백만은 안 되고 16,383자 이하", example = "손을 자주 씻어 주세요.", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = AdvisoryValidationMessage.BODY_REQUIRED)
    @Pattern(regexp = AdvisoryValidationMessage.HAS_VISIBLE_CHARACTER_REGEXP, message = AdvisoryValidationMessage.BODY_REQUIRED)
    @Size(max = AdvisoryValidationMessage.BODY_MAX_LENGTH, message = AdvisoryValidationMessage.BODY_TOO_LONG)
    String body,

    @Schema(description = "수정 메모 (선택, 500자 이하). 이력에 남고 운영자에게만 보인다 — 사용자 화면에는 보이지 않는다", example = "\"유행\" 표현 삭제")
    @Size(max = AdvisoryValidationMessage.MEMO_MAX_LENGTH, message = AdvisoryValidationMessage.MEMO_TOO_LONG)
    String memo,

    @Schema(description = "[필수] 화면이 읽은 안내 버전. 지금 버전과 다르면 409 ADVISORY_006", example = "0", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = AdvisoryValidationMessage.VERSION_REQUIRED)
    @PositiveOrZero(message = AdvisoryValidationMessage.VERSION_INVALID)
    Long version
) {

}
