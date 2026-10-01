package com.sneezecast.domainlayer.auth.adapter.in.web.dto.request;

import com.sneezecast.domainlayer.auth.application.exception.AuthValidationMessage;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "이메일 인증코드 검증 요청 DTO")
public record AuthEmailCodeVerifyRequest(
    @Schema(description = "인증코드를 받은 이메일 주소 (100자 이하)", example = "user@example.com", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = AuthValidationMessage.EMAIL_REQUIRED)
    @Size(max = 100, message = AuthValidationMessage.EMAIL_LENGTH_INVALID)
    @Email(message = AuthValidationMessage.EMAIL_FORMAT_INVALID)
    String email,

    @Schema(description = "메일로 받은 인증코드 (대문자 · 숫자 8자)", example = "A3K7MP2X", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = AuthValidationMessage.EMAIL_CODE_REQUIRED)
    String code
) {

}
