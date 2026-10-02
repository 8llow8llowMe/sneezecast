package com.sneezecast.domainlayer.member.adapter.in.web.dto.request;

import com.sneezecast.domainlayer.member.application.exception.MemberValidationMessage;
import com.sneezecast.domainlayer.member.domain.policy.MemberInputPolicy;
import com.sneezecast.global.validation.StrippedSize;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "내 정보 수정 요청 DTO")
public record MemberMyInfoUpdateRequest(
    @Schema(description = "닉네임 (앞뒤 공백을 지운 뒤 2~10자, 가입과 같은 규칙). 앞뒤 공백은 지우고 저장한다", example = "재채기탐정",
        requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = MemberValidationMessage.NICKNAME_REQUIRED)
    @StrippedSize(min = MemberInputPolicy.NICKNAME_MIN_LENGTH, max = MemberInputPolicy.NICKNAME_MAX_LENGTH,
        message = MemberValidationMessage.NICKNAME_LENGTH_INVALID)
    String nickname
) {

}
