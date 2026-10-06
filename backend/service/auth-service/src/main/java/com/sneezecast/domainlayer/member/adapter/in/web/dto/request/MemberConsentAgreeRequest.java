package com.sneezecast.domainlayer.member.adapter.in.web.dto.request;

import com.sneezecast.domainlayer.member.application.exception.MemberValidationMessage;
import com.sneezecast.domainlayer.member.domain.enums.ConsentType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(description = "동의 요청 DTO (약관 재동의 · 건강정보 동의)")
public record MemberConsentAgreeRequest(
    @Schema(description = "동의 항목. TERMS_OF_SERVICE · PRIVACY_POLICY(약관 재동의) · SENSITIVE_HEALTH_INFO(건강정보 동의). AGE_OVER_19 는 받지 않는다",
        example = "SENSITIVE_HEALTH_INFO", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = MemberValidationMessage.CONSENT_TYPE_REQUIRED)
    ConsentType type,

    @Schema(description = "화면이 보여 준 문서 버전 (프론트 legal 상수, 20자 이하). 서버 현재 버전과 같아야 한다", example = "2026-10-01",
        requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = MemberValidationMessage.DOCUMENT_VERSION_REQUIRED)
    @Size(max = MemberValidationMessage.DOCUMENT_VERSION_MAX_LENGTH, message = MemberValidationMessage.DOCUMENT_VERSION_LENGTH_INVALID)
    String documentVersion
) {

}
