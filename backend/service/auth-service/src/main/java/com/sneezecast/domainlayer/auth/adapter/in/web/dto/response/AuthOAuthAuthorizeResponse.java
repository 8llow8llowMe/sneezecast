package com.sneezecast.domainlayer.auth.adapter.in.web.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "카카오 인가 주소 응답 DTO. state 는 주소에 실려 있고, 같은 값이 HttpOnly 쿠키(oauthState)로도 내려간다")
public record AuthOAuthAuthorizeResponse(
    @Schema(description = "카카오 동의 화면 주소. 화면은 이 주소로 이동(location.assign)한다",
        example = "https://kauth.kakao.com/oauth/authorize?response_type=code&client_id=...&redirect_uri=...&scope=account_email,profile_nickname&state=...")
    String authorizeUrl
) {

    /** 주소에 state 가 실려 있어 로그 · 예외 메시지에 흘리지 않는다. */
    @Override
    public String toString() {
        return "AuthOAuthAuthorizeResponse[authorizeUrl=****]";
    }
}
