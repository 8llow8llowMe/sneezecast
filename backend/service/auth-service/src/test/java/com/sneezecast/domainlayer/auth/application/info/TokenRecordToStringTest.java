package com.sneezecast.domainlayer.auth.application.info;

import static org.assertj.core.api.Assertions.assertThat;

import com.sneezecast.domainlayer.auth.adapter.in.web.dto.request.AuthGeneralLoginRequest;
import com.sneezecast.domainlayer.auth.adapter.in.web.dto.response.AuthTokenResponse;
import com.sneezecast.domainlayer.auth.application.command.AuthGeneralLoginCommand;
import com.sneezecast.security.common.enums.SecurityRole;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 토큰 · 비밀번호 원문을 담는 record 는 기본 {@code toString} 이 값을 그대로 찍는다. 로그 한 줄 · 예외 메시지 하나로 토큰이 새지 않게 가린 것을 고정한다.
 */
class TokenRecordToStringTest {

    private static final String ACCESS = "eyJhbGciOiJIUzUxMiJ9.access-secret";
    private static final String REFRESH = "eyJhbGciOiJIUzUxMiJ9.refresh-secret";

    @Test
    @DisplayName("로그인 · 재발급 결과와 응답은 access · refresh 원문을 가린다 — 식별 · 진단 값은 남긴다")
    void tokenHoldersMaskSecrets() {
        AuthTokenResponse response = AuthTokenResponse.builder().memberId("42").role("USER").accessToken(ACCESS).accessTokenExpiresIn(900)
            .pendingConsents(List.of()).reportWritable(true).build();
        AuthTokenInfo info = AuthTokenInfo.builder().memberId(42L).role(SecurityRole.USER).accessToken(ACCESS).accessTokenExpiresIn(900)
            .refreshToken(REFRESH).pendingConsents(List.of()).reportWritable(true).build();

        for (Object holder : List.of(response, info, AuthCookieResult.of(response, REFRESH))) {
            assertThat(holder.toString()).doesNotContain(ACCESS).doesNotContain(REFRESH).contains("****");
        }
        assertThat(info.toString()).contains("memberId=42").contains("accessTokenExpiresIn=900");
    }

    @Test
    @DisplayName("로그인 요청 · 명령은 비밀번호를 가린다")
    void loginInputsMaskPassword() {
        assertThat(new AuthGeneralLoginRequest("user@example.com", "P@ssw0rd!").toString()).doesNotContain("P@ssw0rd!");
        assertThat(AuthGeneralLoginCommand.builder().email("user@example.com").password("P@ssw0rd!").clientIp("203.0.113.10").deviceLabel("Mac · Chrome")
            .build().toString()).doesNotContain("P@ssw0rd!").doesNotContain("203.0.113.10");
    }
}
