package com.sneezecast.domainlayer.auth.adapter.in.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sneezecast.domainlayer.auth.adapter.in.web.dto.response.AuthOAuthAuthorizeResponse;
import com.sneezecast.domainlayer.auth.adapter.in.web.dto.response.AuthOAuthLoginResponse;
import com.sneezecast.domainlayer.auth.adapter.in.web.dto.response.AuthTokenResponse;
import com.sneezecast.domainlayer.auth.adapter.in.web.exception.AuthExceptionHandler;
import com.sneezecast.domainlayer.auth.adapter.in.web.support.ClientIpResolver;
import com.sneezecast.domainlayer.auth.adapter.in.web.support.DeviceLabelResolver;
import com.sneezecast.domainlayer.auth.adapter.in.web.support.OAuthCookieProvider;
import com.sneezecast.domainlayer.auth.adapter.in.web.support.RefreshCookieProvider;
import com.sneezecast.domainlayer.auth.application.command.AuthOAuthLoginCommand;
import com.sneezecast.domainlayer.auth.application.command.AuthOAuthSignupCommand;
import com.sneezecast.domainlayer.auth.application.exception.AuthErrorCode;
import com.sneezecast.domainlayer.auth.application.exception.AuthException;
import com.sneezecast.domainlayer.auth.application.info.AuthCookieResult;
import com.sneezecast.domainlayer.auth.application.info.AuthOAuthCookieResult;
import com.sneezecast.domainlayer.auth.application.info.AuthOAuthCookieResult.Cookie;
import com.sneezecast.domainlayer.auth.application.port.in.AuthOAuthWebUseCase;
import com.sneezecast.domainlayer.member.adapter.in.web.exception.MemberExceptionHandler;
import com.sneezecast.domainlayer.member.application.exception.MemberErrorCode;
import com.sneezecast.domainlayer.member.application.exception.MemberException;
import com.sneezecast.domainlayer.member.domain.enums.OAuthProvider;
import com.sneezecast.global.properties.OAuthLoginProperties;
import com.sneezecast.security.auth.jwt.JwtAuthProperties;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 카카오 로그인 컨트롤러 — 쿠키 3종의 속성 · 삭제, 결과별 쿠키, 요청 검증 코드를 실제 MVC 경로(Bean Validation + advice)로 본다. 유스케이스는 mock 이다.
 */
class AuthKakaoWebControllerTest {

    private static final String KEY = "sneezecast-auth-controller-test-key-0123456789abcdef0123456789abcdef0123456789";
    private static final String STATE = "q3J9x0b2V7mZkR1sT8uYw4nE6cA5dH0gLpF2iO9jK3M";
    private static final String LOGIN_BODY = "{\"code\":\"kakao-code\",\"state\":\"" + STATE + "\"}";
    private static final String SIGNUP_BODY = "{\"termsAgreed\":true,\"privacyAgreed\":true,\"ageOver19Confirmed\":true}";
    private static final String IPHONE_SAFARI = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) "
        + "Version/18.0 Mobile/15E148 Safari/604.1";
    private static final AuthTokenResponse TOKEN_RESPONSE = AuthTokenResponse.builder().memberId("42").role("USER").accessToken("access")
        .accessTokenExpiresIn(900).pendingConsents(List.of()).reportWritable(false).build();

    private AuthOAuthWebUseCase useCase;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        useCase = mock(AuthOAuthWebUseCase.class);
        RefreshCookieProvider refreshCookieProvider = new RefreshCookieProvider(new JwtAuthProperties(KEY, Duration.ofMinutes(15), KEY, Duration.ofDays(14)));
        OAuthCookieProvider oAuthCookieProvider = new OAuthCookieProvider(
            new OAuthLoginProperties(Duration.ofMinutes(10), Duration.ofMinutes(30), Duration.ofMinutes(10), 30, Duration.ofMinutes(10)));
        mockMvc = MockMvcBuilders.standaloneSetup(new AuthKakaoWebController(useCase, new ClientIpResolver(), new DeviceLabelResolver(), refreshCookieProvider,
                oAuthCookieProvider))
            .setControllerAdvice(new AuthExceptionHandler(), new MemberExceptionHandler())
            .build();
    }

    @Test
    @DisplayName("인가 — 주소를 주고 state 쿠키를 HttpOnly · Secure · SameSite=Strict · Path=/api/v1/auth · Max-Age=600 으로 심는다. 받다 만 표 쿠키 둘은 지운다")
    void authorizeSetsStateCookie() throws Exception {
        when(useCase.authorize(eq(OAuthProvider.KAKAO), eq(false), any()))
            .thenReturn(AuthOAuthCookieResult.of(new AuthOAuthAuthorizeResponse("https://kauth.kakao.com/oauth/authorize?state=" + STATE), Cookie.STATE, STATE));

        MvcResult result = mockMvc.perform(get("/api/v1/auth/kakao/authorize"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dataBody.authorizeUrl").value("https://kauth.kakao.com/oauth/authorize?state=" + STATE))
            .andReturn();

        assertThat(setCookies(result)).hasSize(3)
            .anySatisfy(cookie -> assertThat(cookie).startsWith("oauthState=" + STATE + ";")
                .contains("Path=/api/v1/auth", "Max-Age=600", "Secure", "HttpOnly", "SameSite=Strict"))
            .anySatisfy(cookie -> assertThat(cookie).startsWith("oauthSignupTicket=;").contains("Max-Age=0", "Path=/api/v1/auth"))
            .anySatisfy(cookie -> assertThat(cookie).startsWith("oauthLinkTicket=;").contains("Max-Age=0", "Path=/api/v1/auth"));
    }

    @Test
    @DisplayName("인가 — switchAccount=true 와 X-Real-IP 를 그대로 넘긴다")
    void authorizeForwardsSwitchAccountAndClientIp() throws Exception {
        when(useCase.authorize(eq(OAuthProvider.KAKAO), eq(true), any()))
            .thenReturn(AuthOAuthCookieResult.of(new AuthOAuthAuthorizeResponse("u"), Cookie.STATE, STATE));

        mockMvc.perform(get("/api/v1/auth/kakao/authorize").param("switchAccount", "true").header("X-Real-IP", "203.0.113.10"))
            .andExpect(status().isOk());

        verify(useCase).authorize(OAuthProvider.KAKAO, true, "203.0.113.10");
    }

    @Test
    @DisplayName("인가 — IP 상한을 넘으면 429 AUTH_028 봉투이고 state 쿠키를 심지 않는다(표 쿠키는 지운다)")
    void authorizeIpLimitIsTooManyRequests() throws Exception {
        doThrow(new AuthException(AuthErrorCode.OAUTH_AUTHORIZE_IP_LIMITED)).when(useCase).authorize(any(), anyBoolean(), any());

        MvcResult result = mockMvc.perform(get("/api/v1/auth/kakao/authorize"))
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("AUTH_028"))
            .andReturn();

        assertThat(setCookies(result)).hasSize(2).noneSatisfy(cookie -> assertThat(cookie).startsWith("oauthState="));
    }

    @Test
    @DisplayName("로그인 — code · state · 쿠키 state · 기기 이름을 명령으로 넘기고, LOGGED_IN 이면 state 쿠키를 지우고 refresh 쿠키를 심는다")
    void loginLoggedInSetsRefreshCookieAndClearsState() throws Exception {
        when(useCase.login(any())).thenReturn(AuthOAuthCookieResult.of(AuthOAuthLoginResponse.builder().result("LOGGED_IN").memberId("42")
            .accessToken("access").accessTokenExpiresIn(900L).pendingConsents(List.of()).reportWritable(false).build(), Cookie.REFRESH_TOKEN, "refresh-jwt"));

        MvcResult result = mockMvc.perform(post("/api/v1/auth/kakao/login").contentType(MediaType.APPLICATION_JSON).content(LOGIN_BODY)
                .cookie(new jakarta.servlet.http.Cookie(OAuthCookieProvider.OAUTH_STATE_COOKIE, STATE)).header(HttpHeaders.USER_AGENT, IPHONE_SAFARI))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dataBody.result").value("LOGGED_IN"))
            .andExpect(jsonPath("$.dataBody.accessTokenExpiresIn").value(900))
            .andExpect(jsonPath("$.dataBody.nickname").doesNotExist())
            .andReturn();

        ArgumentCaptor<AuthOAuthLoginCommand> command = ArgumentCaptor.forClass(AuthOAuthLoginCommand.class);
        verify(useCase).login(command.capture());
        assertThat(command.getValue()).isEqualTo(AuthOAuthLoginCommand.builder().provider(OAuthProvider.KAKAO).code("kakao-code").state(STATE)
            .cookieState(STATE).deviceLabel("iPhone · Safari").build());
        assertThat(setCookies(result)).hasSize(4)
            .anySatisfy(cookie -> assertThat(cookie).startsWith("oauthState=;").contains("Max-Age=0", "Path=/api/v1/auth", "HttpOnly", "Secure"))
            .anySatisfy(cookie -> assertThat(cookie).startsWith("refreshToken=refresh-jwt;").contains("Max-Age=1209600", "SameSite=Strict"))
            // 로그인됐으니 받다 만 표는 둘 다 지운다.
            .anySatisfy(cookie -> assertThat(cookie).startsWith("oauthSignupTicket=;").contains("Max-Age=0"))
            .anySatisfy(cookie -> assertThat(cookie).startsWith("oauthLinkTicket=;").contains("Max-Age=0"));
    }

    @Test
    @DisplayName("로그인 — SIGNUP_REQUIRED 면 가입표 쿠키(Max-Age=1800)를 심고 연결 확인표 쿠키를, LINK_REQUIRED 면 연결 확인표 쿠키(Max-Age=600)를 심고 가입표 쿠키를 지운다")
    void loginTicketsSetTicketCookies() throws Exception {
        when(useCase.login(any())).thenReturn(AuthOAuthCookieResult.of(AuthOAuthLoginResponse.builder().result("SIGNUP_REQUIRED").nickname("재채기탐정").build(),
            Cookie.SIGNUP_TICKET, "signup-ticket"));
        MvcResult signup = mockMvc.perform(post("/api/v1/auth/kakao/login").contentType(MediaType.APPLICATION_JSON).content(LOGIN_BODY))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dataBody.result").value("SIGNUP_REQUIRED"))
            .andExpect(jsonPath("$.dataBody.nickname").value("재채기탐정"))
            .andExpect(jsonPath("$.dataBody.accessToken").doesNotExist())
            .andReturn();
        assertThat(setCookies(signup)).hasSize(3)
            .anySatisfy(cookie -> assertThat(cookie).startsWith("oauthSignupTicket=signup-ticket;")
                .contains("Max-Age=1800", "Path=/api/v1/auth", "HttpOnly", "Secure", "SameSite=Strict"))
            .anySatisfy(cookie -> assertThat(cookie).startsWith("oauthLinkTicket=;").contains("Max-Age=0"))
            .noneSatisfy(cookie -> assertThat(cookie).startsWith("refreshToken="));

        when(useCase.login(any())).thenReturn(AuthOAuthCookieResult.of(AuthOAuthLoginResponse.builder().result("LINK_REQUIRED").email("u***@example.com").build(),
            Cookie.LINK_TICKET, "link-ticket"));
        MvcResult link = mockMvc.perform(post("/api/v1/auth/kakao/login").contentType(MediaType.APPLICATION_JSON).content(LOGIN_BODY))
            .andExpect(jsonPath("$.dataBody.email").value("u***@example.com"))
            .andReturn();
        assertThat(setCookies(link)).hasSize(3)
            .anySatisfy(cookie -> assertThat(cookie).startsWith("oauthLinkTicket=link-ticket;").contains("Max-Age=600"))
            .anySatisfy(cookie -> assertThat(cookie).startsWith("oauthSignupTicket=;").contains("Max-Age=0"));
    }

    @Test
    @DisplayName("로그인 실패도 state 쿠키를 지운다 — AUTH_020 · AUTH_022(503) · MEMBER_002 는 봉투로 나간다")
    void loginFailureStillClearsStateCookie() throws Exception {
        for (RuntimeException failure : List.of(new AuthException(AuthErrorCode.OAUTH_STATE_INVALID), new AuthException(AuthErrorCode.OAUTH_PROVIDER_UNAVAILABLE),
            new MemberException(MemberErrorCode.WITHDRAWN_MEMBER))) {
            doThrow(failure).when(useCase).login(any());
            String code = failure instanceof AuthException auth ? auth.getErrorCode().getCode() : ((MemberException) failure).getErrorCode().getCode();

            MvcResult result = mockMvc.perform(post("/api/v1/auth/kakao/login").contentType(MediaType.APPLICATION_JSON).content(LOGIN_BODY))
                .andExpect(jsonPath("$.dataHeader.resultCode").value(code))
                .andReturn();
            assertThat(setCookies(result)).singleElement().asString().startsWith("oauthState=;").contains("Max-Age=0");
        }
    }

    @Test
    @DisplayName("로그인 요청 검증 — code 누락 AUTH_117 · 512자 초과 AUTH_118, state 누락 AUTH_119 · 100자 초과 AUTH_120. 쿠키를 건드리지 않고 유스케이스를 부르지 않는다")
    void loginValidation() throws Exception {
        expectLoginValidation("{\"state\":\"" + STATE + "\"}", "AUTH_117");
        expectLoginValidation("{\"code\":\"" + "c".repeat(513) + "\",\"state\":\"" + STATE + "\"}", "AUTH_118");
        expectLoginValidation("{\"code\":\"kakao-code\",\"state\":\" \"}", "AUTH_119");
        expectLoginValidation("{\"code\":\"kakao-code\",\"state\":\"" + "s".repeat(101) + "\"}", "AUTH_120");
        verifyNoInteractions(useCase);
    }

    @Test
    @DisplayName("가입 — 가입표 쿠키 · 동의 · 기기 이름을 넘기고, 가입표 쿠키를 지우고 refresh 쿠키를 심는다(가입 직후 로그인)")
    void signupIssuesRefreshCookieAndClearsTicket() throws Exception {
        when(useCase.signup(any())).thenReturn(AuthCookieResult.of(TOKEN_RESPONSE, "refresh-jwt"));

        MvcResult result = mockMvc.perform(post("/api/v1/auth/kakao/signup").contentType(MediaType.APPLICATION_JSON).content(SIGNUP_BODY)
                .cookie(new jakarta.servlet.http.Cookie(OAuthCookieProvider.OAUTH_SIGNUP_TICKET_COOKIE, "signup-ticket"))
                .header(HttpHeaders.USER_AGENT, IPHONE_SAFARI))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dataBody.accessToken").value("access"))
            .andReturn();

        ArgumentCaptor<AuthOAuthSignupCommand> command = ArgumentCaptor.forClass(AuthOAuthSignupCommand.class);
        verify(useCase).signup(command.capture());
        assertThat(command.getValue()).isEqualTo(AuthOAuthSignupCommand.builder().signupTicket("signup-ticket").termsAgreed(true).privacyAgreed(true)
            .ageOver19Confirmed(true).deviceLabel("iPhone · Safari").build());
        assertThat(setCookies(result)).hasSize(2)
            .anySatisfy(cookie -> assertThat(cookie).startsWith("oauthSignupTicket=;").contains("Max-Age=0", "Path=/api/v1/auth"))
            .anySatisfy(cookie -> assertThat(cookie).startsWith("refreshToken=refresh-jwt;"));
    }

    @Test
    @DisplayName("가입 — 필수 체크가 빠지면 AUTH_110 · 111 · 112 이고 가입표 쿠키를 지우지 않는다(고쳐서 다시 보낼 수 있다)")
    void signupValidationKeepsTicketCookie() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/kakao/signup").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("AUTH_110"))
            .andExpect(jsonPath("$.dataHeader.fieldErrors[1].code").value("AUTH_111"))
            .andExpect(jsonPath("$.dataHeader.fieldErrors[2].code").value("AUTH_112"))
            .andReturn();

        assertThat(setCookies(result)).isEmpty();
        verifyNoInteractions(useCase);
    }

    @Test
    @DisplayName("가입 — 표 만료 AUTH_025(400) · 중복 MEMBER_001(409) 이어도 가입표 쿠키를 지운다")
    void signupFailuresClearTicketCookie() throws Exception {
        when(useCase.signup(any())).thenThrow(new AuthException(AuthErrorCode.OAUTH_SIGNUP_TICKET_EXPIRED));
        MvcResult expired = mockMvc.perform(post("/api/v1/auth/kakao/signup").contentType(MediaType.APPLICATION_JSON).content(SIGNUP_BODY))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("AUTH_025"))
            .andReturn();
        assertThat(setCookies(expired)).singleElement().asString().startsWith("oauthSignupTicket=;");

        doThrow(new MemberException(MemberErrorCode.EXIST_MEMBER_EMAIL)).when(useCase).signup(any());
        mockMvc.perform(post("/api/v1/auth/kakao/signup").contentType(MediaType.APPLICATION_JSON).content(SIGNUP_BODY))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("MEMBER_001"));
    }

    @Test
    @DisplayName("연결 — 확인표 쿠키 · 기기 이름을 넘기고, 확인표 쿠키를 지우고 refresh 쿠키를 심는다. 실패(AUTH_026 · AUTH_027 409)도 쿠키를 지운다")
    void linkIssuesRefreshCookieAndClearsTicket() throws Exception {
        when(useCase.link(eq("link-ticket"), anyString())).thenReturn(AuthCookieResult.of(TOKEN_RESPONSE, "refresh-jwt"));

        MvcResult result = mockMvc.perform(post("/api/v1/auth/kakao/link")
                .cookie(new jakarta.servlet.http.Cookie(OAuthCookieProvider.OAUTH_LINK_TICKET_COOKIE, "link-ticket")).header(HttpHeaders.USER_AGENT, IPHONE_SAFARI))
            .andExpect(status().isOk())
            .andReturn();
        verify(useCase).link("link-ticket", "iPhone · Safari");
        assertThat(setCookies(result)).hasSize(2)
            .anySatisfy(cookie -> assertThat(cookie).startsWith("oauthLinkTicket=;").contains("Max-Age=0"))
            .anySatisfy(cookie -> assertThat(cookie).startsWith("refreshToken=refresh-jwt;"));

        doThrow(new AuthException(AuthErrorCode.OAUTH_LINK_NOT_ALLOWED)).when(useCase).link(any(), anyString());
        MvcResult conflict = mockMvc.perform(post("/api/v1/auth/kakao/link"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("AUTH_027"))
            .andReturn();
        assertThat(setCookies(conflict)).singleElement().asString().startsWith("oauthLinkTicket=;");
    }

    private void expectLoginValidation(String body, String code) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/kakao/login").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.dataHeader.resultCode").value(code))
            .andReturn();
        assertThat(setCookies(result)).isEmpty();
    }

    private static List<String> setCookies(MvcResult result) {
        return result.getResponse().getHeaders(HttpHeaders.SET_COOKIE);
    }
}
