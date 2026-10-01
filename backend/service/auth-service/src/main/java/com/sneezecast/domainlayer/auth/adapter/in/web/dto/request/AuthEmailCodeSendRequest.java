package com.sneezecast.domainlayer.auth.adapter.in.web.dto.request;

import com.sneezecast.domainlayer.auth.application.exception.AuthValidationMessage;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "이메일 인증코드 발송 요청 DTO")
public record AuthEmailCodeSendRequest(
    @Schema(description = "인증코드를 받을 이메일 주소 (100자 이하)", example = "user@example.com", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = AuthValidationMessage.EMAIL_REQUIRED)
    @Size(max = 100, message = AuthValidationMessage.EMAIL_LENGTH_INVALID)
    @Email(message = AuthValidationMessage.EMAIL_FORMAT_INVALID)
    String email
) {

}
