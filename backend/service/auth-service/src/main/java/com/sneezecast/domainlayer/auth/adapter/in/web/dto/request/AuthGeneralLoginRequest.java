package com.sneezecast.domainlayer.auth.adapter.in.web.dto.request;

import com.sneezecast.domainlayer.auth.application.exception.AuthValidationMessage;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "이메일 로그인 요청 DTO")
public record AuthGeneralLoginRequest(
    @Schema(description = "가입한 이메일 주소 (100자 이하). 대소문자 · 앞뒤 공백은 무시한다", example = "user@example.com",
        requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = AuthValidationMessage.EMAIL_REQUIRED)
    @Size(max = 100, message = AuthValidationMessage.EMAIL_LENGTH_INVALID)
    @Email(message = AuthValidationMessage.EMAIL_FORMAT_INVALID)
    String email,

    @Schema(description = "비밀번호 (100자 이하). 가입 때의 구성 규칙은 검사하지 않는다", example = "P@ssw0rd!", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = AuthValidationMessage.PASSWORD_REQUIRED)
    @Size(max = 100, message = AuthValidationMessage.LOGIN_PASSWORD_LENGTH_INVALID)
    String password
) {

    /** 비밀번호를 로그 · 예외 메시지에 흘리지 않는다. */
    @Override
    public String toString() {
        return "AuthGeneralLoginRequest[email=****, password=****]";
    }
}
