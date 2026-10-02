package com.sneezecast.domainlayer.auth.adapter.out.oauth.kakao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.sneezecast.domainlayer.auth.application.exception.AuthErrorCode;
import com.sneezecast.domainlayer.auth.application.exception.AuthException;
import com.sneezecast.domainlayer.auth.application.port.out.query.OAuthMemberQueryResult;
import com.sneezecast.global.properties.KakaoOAuthProperties;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 응답 본문은 카카오 REST API 문서의 형식을 줄여 옮겼다 — 토큰 {@code {token_type, access_token, expires_in, refresh_token, ...}}, 사용자 정보
 * {@code {id, kakao_account{profile{nickname}, email, is_email_valid, is_email_verified, ...}}}, 오류 {@code {error, error_description, error_code}}.
 * 실제 카카오는 부르지 않는다.
 */
class KakaoOAuthAdapterTest {

    private static final String TOKEN_URI = "https://kauth.test/oauth/token";
    private static final String USER_INFO_URI = "https://kapi.test/v2/user/me";
    private static final String REDIRECT_URI = "https://dev.sneezecast.com/login/kakao/callback";
    private static final String CLIENT_SECRET = "kakao-client-secret-0123";
    private static final String CODE = "kakao-auth-code-0123";
    private static final String KAKAO_ACCESS = "kakao-access-token-0123";
    private static final String TOKEN_BODY = """
        {"token_type":"bearer","access_token":"%s","expires_in":21599,"refresh_token":"kakao-refresh","refresh_token_expires_in":5183999,
         "scope":"account_email profile_nickname"}""".formatted(KAKAO_ACCESS);

    private MockRestServiceServer server;
    private KakaoOAuthAdapter adapter;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        adapter = new KakaoOAuthAdapter(builder.build(), new KakaoOAuthProperties("kakao-client-id", CLIENT_SECRET, REDIRECT_URI,
            "https://kauth.test/oauth/authorize", TOKEN_URI, USER_INFO_URI, Duration.ofSeconds(2), Duration.ofSeconds(3)));
    }

    @Test
    @DisplayName("인가 주소에는 response_type · client_id · redirect_uri · scope(이메일 · 닉네임만) · state 가 실린다 — 프로필 이미지 동의는 받지 않는다")
    void authorizationUrlCarriesRequiredParameters() {
        UriComponents url = UriComponentsBuilder.fromUriString(adapter.authorizationUrl("state-1", false)).build();

        assertThat(url.getHost()).isEqualTo("kauth.test");
        assertThat(url.getPath()).isEqualTo("/oauth/authorize");
        assertThat(url.getQueryParams().toSingleValueMap()).containsExactlyInAnyOrderEntriesOf(java.util.Map.of(
            "response_type", "code", "client_id", "kakao-client-id", "redirect_uri", REDIRECT_URI, "scope", "account_email,profile_nickname",
            "state", "state-1"));
        assertThat(url.toUriString()).doesNotContain("profile_image").doesNotContain(CLIENT_SECRET);
    }

    @Test
    @DisplayName("다른 카카오 계정으로 계속하기면 prompt=select_account 를 더한다")
    void switchAccountAddsSelectAccountPrompt() {
        UriComponents url = UriComponentsBuilder.fromUriString(adapter.authorizationUrl("state-1", true)).build();

        assertThat(url.getQueryParams().getFirst("prompt")).isEqualTo("select_account");
    }

    @Test
    @DisplayName("토큰 교환은 폼 POST(grant_type · client_id · client_secret · redirect_uri · code)이고, 받은 토큰으로 사용자 정보를 Bearer 로 부른다")
    void exchangesTokenThenFetchesUser() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("client_id", "kakao-client-id");
        form.add("client_secret", CLIENT_SECRET);
        form.add("redirect_uri", REDIRECT_URI);
        form.add("code", CODE);
        server.expect(requestTo(TOKEN_URI)).andExpect(method(HttpMethod.POST))
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
            .andExpect(content().formData(form))
            .andRespond(withSuccess(TOKEN_BODY, MediaType.APPLICATION_JSON));
        server.expect(requestTo(USER_INFO_URI)).andExpect(method(HttpMethod.GET))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + KAKAO_ACCESS))
            .andRespond(withSuccess("""
                {"id":3912345678,"connected_at":"2026-10-01T00:00:00Z","kakao_account":{"profile_nickname_needs_agreement":false,
                 "profile":{"nickname":"재채기탐정","is_default_nickname":false},"has_email":true,"email_needs_agreement":false,
                 "is_email_valid":true,"is_email_verified":true,"email":"User@Example.com"}}""", MediaType.APPLICATION_JSON));

        OAuthMemberQueryResult result = adapter.fetchMember(CODE);

        server.verify();
        assertThat(result).isEqualTo(OAuthMemberQueryResult.builder().email("User@Example.com").emailVerified(true).emailValid(true).nickname("재채기탐정")
            .build());
    }

    @Test
    @DisplayName("이메일 제공에 동의하지 않았으면 이메일이 null 이고, 인증 · 유효 플래그가 없거나 false 면 false 다")
    void missingConsentItemsAreNullOrFalse() {
        expectToken();
        server.expect(requestTo(USER_INFO_URI)).andRespond(withSuccess("""
            {"id":1,"kakao_account":{"email_needs_agreement":true,"profile":{"nickname":"닉"}}}""", MediaType.APPLICATION_JSON));

        OAuthMemberQueryResult result = adapter.fetchMember(CODE);

        assertThat(result.email()).isNull();
        assertThat(result.emailVerified()).isFalse();
        assertThat(result.emailValid()).isFalse();
        assertThat(result.nickname()).isEqualTo("닉");
    }

    @Test
    @DisplayName("kakao_account 가 없으면 모든 값이 비어 있다 — 이메일 필수 판정은 처리기가 한다")
    void missingAccountYieldsEmptyResult() {
        expectToken();
        server.expect(requestTo(USER_INFO_URI)).andRespond(withSuccess("{\"id\":1}", MediaType.APPLICATION_JSON));

        assertThat(adapter.fetchMember(CODE)).isEqualTo(OAuthMemberQueryResult.builder().build());
    }

    @Test
    @DisplayName("카카오가 인가 코드를 거부하면(400 invalid_grant) OAUTH_LOGIN_FAILED 다 — 사용자 정보는 부르지 않는다")
    void rejectedCodeIsLoginFailed() {
        server.expect(requestTo(TOKEN_URI)).andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
            .body("{\"error\":\"invalid_grant\",\"error_description\":\"authorization code not found for code=" + CODE + "\",\"error_code\":\"KOE320\"}"));

        assertThat(failure()).isEqualTo(AuthErrorCode.OAUTH_LOGIN_FAILED);
        server.verify();
    }

    @Test
    @DisplayName("앱 키 · 시크릿 오류(401 invalid_client)도 4xx 라 OAUTH_LOGIN_FAILED 다")
    void invalidClientIsLoginFailed() {
        server.expect(requestTo(TOKEN_URI)).andRespond(withStatus(HttpStatus.UNAUTHORIZED).contentType(MediaType.APPLICATION_JSON)
            .body("{\"error\":\"invalid_client\",\"error_code\":\"KOE010\"}"));

        assertThat(failure()).isEqualTo(AuthErrorCode.OAUTH_LOGIN_FAILED);
    }

    @Test
    @DisplayName("사용자 정보 요청이 4xx(토큰 거부)면 OAUTH_LOGIN_FAILED 다")
    void userInfoRejectionIsLoginFailed() {
        expectToken();
        server.expect(requestTo(USER_INFO_URI)).andRespond(withStatus(HttpStatus.UNAUTHORIZED).body("{\"msg\":\"this access token does not exist\",\"code\":-401}"));

        assertThat(failure()).isEqualTo(AuthErrorCode.OAUTH_LOGIN_FAILED);
    }

    @Test
    @DisplayName("카카오 5xx 는 토큰 교환 · 사용자 정보 어느 쪽이든 OAUTH_PROVIDER_UNAVAILABLE(503) 이다")
    void serverErrorsAreProviderUnavailable() {
        server.expect(requestTo(TOKEN_URI)).andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        assertThat(failure()).isEqualTo(AuthErrorCode.OAUTH_PROVIDER_UNAVAILABLE);
        assertThat(AuthErrorCode.OAUTH_PROVIDER_UNAVAILABLE.getHttpStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

        server.reset();
        expectToken();
        server.expect(requestTo(USER_INFO_URI)).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        assertThat(failure()).isEqualTo(AuthErrorCode.OAUTH_PROVIDER_UNAVAILABLE);
    }

    @Test
    @DisplayName("응답 지연(read timeout) · 연결 실패는 OAUTH_PROVIDER_UNAVAILABLE 이고, 예외에 원인 · 비밀값을 붙이지 않는다")
    void timeoutsAndConnectionFailuresAreProviderUnavailable() {
        server.expect(requestTo(TOKEN_URI)).andRespond(withException(new SocketTimeoutException("Read timed out")));
        AuthException timeout = catchThrowableOfType(AuthException.class, () -> adapter.fetchMember(CODE));
        assertThat(timeout.getErrorCode()).isEqualTo(AuthErrorCode.OAUTH_PROVIDER_UNAVAILABLE);
        assertThat(timeout.getCause()).isNull();
        assertThat(timeout.getMessage()).doesNotContain(CODE).doesNotContain(CLIENT_SECRET);

        server.reset();
        expectToken();
        server.expect(requestTo(USER_INFO_URI)).andRespond(withException(new ConnectException("Connection refused")));
        assertThat(failure()).isEqualTo(AuthErrorCode.OAUTH_PROVIDER_UNAVAILABLE);
    }

    @Test
    @DisplayName("200 인데 access_token 이 없거나 본문이 JSON 이 아니면 OAUTH_PROVIDER_UNAVAILABLE 이다")
    void malformedResponsesAreProviderUnavailable() {
        server.expect(requestTo(TOKEN_URI)).andRespond(withSuccess("{\"token_type\":\"bearer\"}", MediaType.APPLICATION_JSON));
        assertThat(failure()).isEqualTo(AuthErrorCode.OAUTH_PROVIDER_UNAVAILABLE);

        server.reset();
        server.expect(requestTo(TOKEN_URI)).andRespond(withSuccess("<html>oops</html>", MediaType.APPLICATION_JSON));
        assertThat(failure()).isEqualTo(AuthErrorCode.OAUTH_PROVIDER_UNAVAILABLE);
    }

    private void expectToken() {
        server.expect(requestTo(TOKEN_URI)).andRespond(withSuccess(TOKEN_BODY, MediaType.APPLICATION_JSON));
    }

    private AuthErrorCode failure() {
        return catchThrowableOfType(AuthException.class, () -> adapter.fetchMember(CODE)).getErrorCode();
    }
}
