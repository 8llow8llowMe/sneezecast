package com.sneezecast.domainlayer.auth.adapter.in.web.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "비밀번호 재설정 인증코드 확인 응답 DTO")
public record AuthPasswordResetTokenResponse(
    @Schema(description = "1회용 재설정 토큰 (기본 15분). 새 비밀번호와 함께 POST /api/v1/auth/password/reset 으로 보낸다",
        example = "q3J9x0b2V7mZkR1sT8uYw4nE6cA5dH0gLpF2iO9jK3M")
    String resetToken
) {

    /** 토큰 원문은 로그 · 예외 메시지에 흘리지 않는다. */
    @Override
    public String toString() {
        return "AuthPasswordResetTokenResponse[resetToken=****]";
    }
}
