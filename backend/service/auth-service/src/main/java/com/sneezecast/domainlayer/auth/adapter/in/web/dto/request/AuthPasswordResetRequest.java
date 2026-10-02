package com.sneezecast.domainlayer.auth.adapter.in.web.dto.request;

import com.sneezecast.domainlayer.auth.application.exception.AuthValidationMessage;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(description = "비밀번호 재설정 요청 DTO")
public record AuthPasswordResetRequest(
    @Schema(description = "재설정 인증코드 확인 응답의 resetToken (1회용). 화면은 메모리에만 들고 주소 · 저장소에 남기지 않는다",
        example = "q3J9x0b2V7mZkR1sT8uYw4nE6cA5dH0gLpF2iO9jK3M", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = AuthValidationMessage.RESET_TOKEN_REQUIRED)
    @Size(max = 100, message = AuthValidationMessage.RESET_TOKEN_LENGTH_INVALID)
    String resetToken,

    @Schema(description = "새 비밀번호 (공백 없이 영문자 · 숫자 각 1자 이상, 8~20자. 특수문자는 선택)", example = "Sneeze2026!",
        requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = AuthValidationMessage.PASSWORD_REQUIRED)
    @Size(min = 8, max = 20, message = AuthValidationMessage.PASSWORD_LENGTH_INVALID)
    @Pattern(regexp = AuthValidationMessage.PASSWORD_REGEXP, message = AuthValidationMessage.PASSWORD_PATTERN_INVALID)
    String newPassword
) {

    /** 토큰 · 비밀번호를 로그 · 예외 메시지에 흘리지 않는다. */
    @Override
    public String toString() {
        return "AuthPasswordResetRequest[resetToken=****, newPassword=****]";
    }
}
