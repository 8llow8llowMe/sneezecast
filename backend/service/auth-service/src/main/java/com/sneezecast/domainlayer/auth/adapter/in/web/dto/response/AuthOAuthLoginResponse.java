package com.sneezecast.domainlayer.auth.adapter.in.web.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Builder;

/**
 * 카카오 로그인 응답. {@code result} 에 따라 채워지는 필드가 다르고, 없는 필드는 응답에서 빠진다.
 *
 * <ul>
 *   <li>{@code LOGGED_IN} — 로그인 응답과 같은 필드(memberId · role · accessToken · accessTokenExpiresIn · pendingConsents · reportWritable).
 *       refresh 는 쿠키다.</li>
 *   <li>{@code SIGNUP_REQUIRED} — nickname. 가입표는 쿠키(oauthSignupTicket)다.</li>
 *   <li>{@code LINK_REQUIRED} — email(가린 값). 연결 확인표는 쿠키(oauthLinkTicket)다.</li>
 * </ul>
 * 로그인 응답 필드가 결과에 따라 빠질 수 있어 숫자 · 참거짓도 래퍼다(없으면 null → 응답에서 빠짐).
 */
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "카카오 로그인 응답 DTO. result 에 따라 채워지는 필드가 다르다 — 없는 필드는 빠진다")
public record AuthOAuthLoginResponse(
    @Schema(description = "결과 LOGGED_IN(로그인됨) · SIGNUP_REQUIRED(동의 후 가입) · LINK_REQUIRED(기존 이메일 계정 연결 확인)", example = "LOGGED_IN")
    String result,

    @Schema(description = "[LOGGED_IN] 회원 아이디", example = "1843956734582784")
    String memberId,

    @Schema(description = "[LOGGED_IN] 역할 USER · OPERATOR · ADMIN", example = "USER")
    String role,

    @Schema(description = "[LOGGED_IN] access token. Authorization: Bearer 헤더로 보낸다", example = "eyJhbGciOiJIUzUxMiJ9...")
    String accessToken,

    @Schema(description = "[LOGGED_IN] access token 수명(초)", example = "900")
    Long accessTokenExpiresIn,

    @Schema(description = "[LOGGED_IN] 다시 동의해야 하는 필수 항목(ConsentType 이름). 없으면 빈 목록", example = "[]")
    List<String> pendingConsents,

    @Schema(description = "[LOGGED_IN] 주간 보고를 쓸 수 있는지", example = "true")
    Boolean reportWritable,

    @Schema(description = "[SIGNUP_REQUIRED] 가입하면 쓸 닉네임 (카카오 닉네임을 2~10자로 맞춘 값, 가입 뒤 내 정보에서 바꿀 수 있다)", example = "재채기탐정")
    String nickname,

    @Schema(description = "[LINK_REQUIRED] 연결할 기존 계정의 가린 이메일", example = "d***@example.com")
    String email
) {

    /** access token 원문은 로그 · 예외 메시지에 흘리지 않는다. */
    @Override
    public String toString() {
        return "AuthOAuthLoginResponse[result=" + result + ", memberId=" + memberId + ", accessToken=" + (accessToken == null ? "null" : "****") + "]";
    }
}
