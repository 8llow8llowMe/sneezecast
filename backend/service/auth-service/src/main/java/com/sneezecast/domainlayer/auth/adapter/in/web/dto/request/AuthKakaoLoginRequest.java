package com.sneezecast.domainlayer.auth.adapter.in.web.dto.request;

import com.sneezecast.domainlayer.auth.application.exception.AuthValidationMessage;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 카카오 로그인 콜백 요청 DTO. 인가 코드가 접근 로그 · 브라우저 기록에 남지 않게 GET 쿼리가 아니라 POST 바디로 받는다.
 */
@Schema(description = "카카오 로그인 요청 DTO — 프론트 콜백 페이지가 받은 code · state 를 그대로 넘긴다")
public record AuthKakaoLoginRequest(
    @Schema(description = "카카오가 콜백 주소로 준 인가 코드 (1회용)", example = "x8Kp0bQ2vR7mZkR1sT8uYw4nE6cA5dH0gLpF2iO9jK3M", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = AuthValidationMessage.OAUTH_CODE_REQUIRED)
    @Size(max = 512, message = AuthValidationMessage.OAUTH_CODE_LENGTH_INVALID)
    String code,

    @Schema(description = "카카오가 콜백 주소로 돌려준 state. 인가 요청 때 받은 oauthState 쿠키와 같아야 한다", example = "q3J9x0b2V7mZkR1sT8uYw4nE6cA5dH0gLpF2iO9jK3M",
        requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = AuthValidationMessage.OAUTH_STATE_REQUIRED)
    @Size(max = 100, message = AuthValidationMessage.OAUTH_STATE_LENGTH_INVALID)
    String state
) {

    /** 인가 코드 · state 를 로그 · 예외 메시지에 흘리지 않는다. */
    @Override
    public String toString() {
        return "AuthKakaoLoginRequest[code=****, state=****]";
    }
}
