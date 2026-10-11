package com.sneezecast.domainlayer.advisory.adapter.in.web.dto.request;

import com.sneezecast.domainlayer.advisory.application.exception.AdvisoryValidationMessage;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;

/**
 * 발행 이력 쿼리 파라미터 ({@code ?week=}, #267). 이 주로 끝나는 4주를 본다. 생략하면 현재 주 − 1 로 끝나는 4주다.
 */
public record AdvisoryPublicationHistoryRequest(
    @Schema(description = "[선택] 끝 주 YYYY-Www — 이 주와 그 앞 3주의 안내를 본다. 생략하면 현재 주 − 1. 현재 주 ~ 51주 전만", example = "2026-W40")
    @Pattern(regexp = AdvisoryValidationMessage.ISO_WEEK_REGEXP, message = AdvisoryValidationMessage.ISO_WEEK_FORMAT_INVALID)
    String week
) {

}
