package com.sneezecast.domainlayer.advisory.adapter.in.web.dto.request;

import com.sneezecast.domainlayer.advisory.application.exception.AdvisoryValidationMessage;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 본문 없이 상태만 바꾸는 처리(승인 · 발행)의 요청 본문 — 화면이 읽은 버전 하나다.
 */
public record AdvisoryVersionRequest(
    @Schema(description = "[필수] 화면이 읽은 안내 버전. 지금 버전과 다르면 409 ADVISORY_006", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = AdvisoryValidationMessage.VERSION_REQUIRED)
    @PositiveOrZero(message = AdvisoryValidationMessage.VERSION_INVALID)
    Long version
) {

}
