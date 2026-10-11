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
 * 철회 본문 → RETRACTED. 사유는 필수이고 이력 메모로 남는다 — 운영자에게만 보이고 공개 안내(#215)에는 철회 시각만 보인다.
 */
@Builder
public record AdvisoryRetractRequest(
    @Schema(description = "[필수] 철회 사유. 공백만은 안 되고 500자 이하. 사용자 화면에는 보이지 않는다", example = "집계 오류 확인",
        requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = AdvisoryValidationMessage.REASON_REQUIRED)
    @Pattern(regexp = AdvisoryValidationMessage.HAS_VISIBLE_CHARACTER_REGEXP, message = AdvisoryValidationMessage.REASON_REQUIRED)
    @Size(max = AdvisoryValidationMessage.MEMO_MAX_LENGTH, message = AdvisoryValidationMessage.REASON_TOO_LONG)
    String reason,

    @Schema(description = "[필수] 화면이 읽은 안내 버전. 지금 버전과 다르면 409 ADVISORY_006", example = "3", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = AdvisoryValidationMessage.VERSION_REQUIRED)
    @PositiveOrZero(message = AdvisoryValidationMessage.VERSION_INVALID)
    Long version
) {

}
