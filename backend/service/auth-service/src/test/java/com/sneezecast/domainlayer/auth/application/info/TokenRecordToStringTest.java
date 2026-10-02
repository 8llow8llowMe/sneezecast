package com.sneezecast.domainlayer.auth.application.info;

import static org.assertj.core.api.Assertions.assertThat;

import com.sneezecast.domainlayer.auth.adapter.in.web.dto.request.AuthGeneralLoginRequest;
import com.sneezecast.domainlayer.auth.adapter.in.web.dto.request.AuthKakaoLoginRequest;
import com.sneezecast.domainlayer.auth.adapter.in.web.dto.request.AuthPasswordResetRequest;
import com.sneezecast.domainlayer.auth.adapter.in.web.dto.response.AuthOAuthAuthorizeResponse;
import com.sneezecast.domainlayer.auth.adapter.in.web.dto.response.AuthOAuthLoginResponse;
import com.sneezecast.domainlayer.auth.adapter.in.web.dto.response.AuthPasswordResetTokenResponse;
import com.sneezecast.domainlayer.auth.adapter.in.web.dto.response.AuthTokenResponse;
import com.sneezecast.domainlayer.auth.adapter.out.oauth.kakao.dto.KakaoTokenClientResponse;
import com.sneezecast.domainlayer.auth.adapter.out.oauth.kakao.dto.KakaoUserClientResponse.KakaoAccount;
import com.sneezecast.domainlayer.auth.application.command.AuthGeneralLoginCommand;
import com.sneezecast.domainlayer.auth.application.command.AuthOAuthLoginCommand;
import com.sneezecast.domainlayer.auth.application.command.AuthOAuthSignupCommand;
import com.sneezecast.domainlayer.auth.application.info.AuthOAuthCookieResult.Cookie;
import com.sneezecast.domainlayer.auth.application.model.OAuthLoginDecision;
import com.sneezecast.domainlayer.auth.application.model.OAuthSignupTicket;
import com.sneezecast.domainlayer.auth.application.port.out.query.OAuthMemberQueryResult;
import com.sneezecast.domainlayer.member.adapter.in.web.dto.request.MemberPasswordChangeRequest;
import com.sneezecast.domainlayer.member.domain.enums.OAuthProvider;
import com.sneezecast.global.properties.KakaoOAuthProperties;
import com.sneezecast.security.common.enums.SecurityRole;
import java.time.Duration;
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

    @Test
    @DisplayName("비밀번호 변경 · 재설정 요청과 재설정 토큰 응답은 비밀번호 · 토큰을 가린다")
    void passwordInputsAndResetTokenAreMasked() {
        String token = "q3J9x0b2V7mZkR1sT8uYw4nE6cA5dH0gLpF2iO9jK3M";
        List<Object> holders = List.of(new MemberPasswordChangeRequest("P@ssw0rd!", "Sneeze2026!"), new AuthPasswordResetRequest(token, "Sneeze2026!"),
            new AuthPasswordResetTokenResponse(token));

        for (Object holder : holders) {
            assertThat(holder.toString()).doesNotContain("P@ssw0rd!").doesNotContain("Sneeze2026!").doesNotContain(token).contains("****");
        }
    }

    @Test
    @DisplayName("카카오 로그인의 code · state · 표 · 카카오 토큰 · 이메일 · client secret 은 어느 toString 에도 나오지 않는다")
    void oauthHoldersMaskSecrets() {
        String code = "kakao-authorization-code-0123";
        String state = "state-value-0123456789";
        String ticket = "ticket-value-0123456789";
        String email = "user@example.com";
        String secret = "kakao-client-secret-0123";
        AuthOAuthLoginResponse loginResponse = AuthOAuthLoginResponse.builder().result("LOGGED_IN").memberId("42").accessToken(ACCESS).build();
        List<Object> holders = List.of(
            new AuthKakaoLoginRequest(code, state),
            AuthOAuthLoginCommand.builder().provider(OAuthProvider.KAKAO).code(code).state(state).cookieState(state).deviceLabel("Mac · Chrome").build(),
            AuthOAuthSignupCommand.builder().signupTicket(ticket).termsAgreed(true).build(),
            AuthOAuthCookieResult.of(loginResponse, Cookie.SIGNUP_TICKET, ticket),
            new OAuthAuthorizationInfo("https://kauth.kakao.com/oauth/authorize?state=" + state, state),
            new AuthOAuthAuthorizeResponse("https://kauth.kakao.com/oauth/authorize?state=" + state),
            OAuthLoginDecision.signupRequired(ticket, "재채기탐정"),
            new OAuthSignupTicket(OAuthProvider.KAKAO, email, "재채기탐정"),
            OAuthMemberQueryResult.builder().email(email).emailVerified(true).emailValid(true).nickname("재채기탐정").build(),
            new KakaoTokenClientResponse(ACCESS),
            new KakaoAccount(email, true, true, null),
            new KakaoOAuthProperties("client-id", secret, "https://dev.sneezecast.com/login/kakao/callback", "https://a", "https://t", "https://u",
                Duration.ofSeconds(2), Duration.ofSeconds(3)));

        for (Object holder : holders) {
            assertThat(holder.toString()).as(holder.getClass().getSimpleName())
                .doesNotContain(code).doesNotContain(state).doesNotContain(ticket).doesNotContain(ACCESS).doesNotContain(email).doesNotContain(secret);
        }
    }
}
