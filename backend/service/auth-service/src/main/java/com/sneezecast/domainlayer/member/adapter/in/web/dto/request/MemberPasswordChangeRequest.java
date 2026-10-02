package com.sneezecast.domainlayer.member.adapter.in.web.dto.request;

import com.sneezecast.domainlayer.member.application.exception.MemberValidationMessage;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(description = "비밀번호 변경 요청 DTO")
public record MemberPasswordChangeRequest(
    @Schema(description = "현재 비밀번호 (100자 이하). 가입 때의 구성 규칙은 검사하지 않는다", example = "P@ssw0rd!", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = MemberValidationMessage.CURRENT_PASSWORD_REQUIRED)
    @Size(max = 100, message = MemberValidationMessage.CURRENT_PASSWORD_LENGTH_INVALID)
    String currentPassword,

    @Schema(description = "새 비밀번호 (공백 없이 영문자 · 숫자 각 1자 이상, 8~20자. 특수문자는 선택). 현재와 같아도 된다", example = "Sneeze2026!",
        requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = MemberValidationMessage.NEW_PASSWORD_REQUIRED)
    @Size(min = 8, max = 20, message = MemberValidationMessage.NEW_PASSWORD_LENGTH_INVALID)
    @Pattern(regexp = MemberValidationMessage.PASSWORD_REGEXP, message = MemberValidationMessage.NEW_PASSWORD_PATTERN_INVALID)
    String newPassword
) {

    /** 비밀번호를 로그 · 예외 메시지에 흘리지 않는다. */
    @Override
    public String toString() {
        return "MemberPasswordChangeRequest[currentPassword=****, newPassword=****]";
    }
}
