package com.sneezecast.domainlayer.auth.adapter.in.web.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Builder;

@Builder
@Schema(description = "로그인 · 토큰 재발급 응답 DTO. refresh 토큰은 본문이 아니라 HttpOnly 쿠키(refreshToken)로 내려간다")
public record AuthTokenResponse(
    @Schema(description = "회원 아이디", example = "1843956734582784")
    String memberId,

    @Schema(description = "역할 USER · OPERATOR · ADMIN", example = "USER")
    String role,

    @Schema(description = "access token. Authorization: Bearer 헤더로 보낸다", example = "eyJhbGciOiJIUzUxMiJ9...")
    String accessToken,

    @Schema(description = "access token 수명(초). 만료 전에 재발급한다", example = "900")
    long accessTokenExpiresIn,

    @Schema(description = "다시 동의해야 하는 필수 항목(ConsentType 이름). 문서가 개정되면 채워진다 — 로그인은 됐고, 화면이 재동의로 이끈다. 없으면 빈 목록",
        example = "[\"PRIVACY_POLICY\"]")
    List<String> pendingConsents,

    @Schema(description = "주간 보고를 쓸 수 있는지 (access token 에 report:write 가 실렸는지). 필수 동의가 모두 유효하고 건강정보 동의가 유효해야 true",
        example = "true")
    boolean reportWritable
) {

    /** access token 원문은 로그 · 예외 메시지에 흘리지 않는다. */
    @Override
    public String toString() {
        return "AuthTokenResponse[memberId=" + memberId + ", role=" + role + ", accessToken=****, accessTokenExpiresIn=" + accessTokenExpiresIn
            + ", pendingConsents=" + pendingConsents + ", reportWritable=" + reportWritable + "]";
    }
}
