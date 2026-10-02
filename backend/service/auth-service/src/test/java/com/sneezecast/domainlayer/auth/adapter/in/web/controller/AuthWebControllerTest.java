package com.sneezecast.domainlayer.auth.adapter.in.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.sneezecast.domainlayer.auth.adapter.in.web.dto.item.AuthSessionItem;
import com.sneezecast.domainlayer.auth.adapter.in.web.dto.response.AuthSessionsResponse;
import com.sneezecast.domainlayer.auth.adapter.in.web.dto.response.AuthTokenResponse;
import com.sneezecast.domainlayer.auth.adapter.in.web.exception.AuthExceptionHandler;
import com.sneezecast.domainlayer.auth.adapter.in.web.support.ClientIpResolver;
import com.sneezecast.domainlayer.auth.adapter.in.web.support.DeviceLabelResolver;
import com.sneezecast.domainlayer.auth.adapter.in.web.support.RefreshCookieProvider;
import com.sneezecast.domainlayer.auth.application.command.AuthGeneralLoginCommand;
import com.sneezecast.domainlayer.auth.application.command.AuthGeneralSignupCommand;
import com.sneezecast.domainlayer.auth.application.exception.AuthErrorCode;
import com.sneezecast.domainlayer.auth.application.exception.AuthException;
import com.sneezecast.domainlayer.auth.application.info.AuthCookieResult;
import com.sneezecast.domainlayer.auth.application.port.in.AuthWebUseCase;
import com.sneezecast.domainlayer.member.adapter.in.web.exception.MemberExceptionHandler;
import com.sneezecast.domainlayer.member.application.exception.MemberErrorCode;
import com.sneezecast.domainlayer.member.application.exception.MemberException;
import com.sneezecast.security.auth.jwt.JwtAuthProperties;
import com.sneezecast.security.common.dto.MemberLoginActive;
import com.sneezecast.security.common.enums.SecurityRole;
import com.sneezecast.security.common.jwt.JwtAuthentication;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 요청 검증 · 오류 봉투 · 필드별 코드 · 쿠키 속성을 실제 MVC 경로(Bean Validation + advice)로 본다. 유스케이스는 mock 이다.
 * 인증 주체는 SecurityContext 에 직접 세운다 — 토큰이 없을 때 401 인지(메서드 보안)는 {@code AuthServiceApplicationTests} 가 실제 필터 체인으로 본다.
 */
class AuthWebControllerTest {

    private static final String VALID_SIGNUP = """
        {"email":"User@Example.com","password":"P@ssw0rd!","nickname":" 재채기탐정 ",
         "termsAgreed":true,"privacyAgreed":true,"ageOver19Confirmed":true}""";

    private static final String KEY = "sneezecast-auth-controller-test-key-0123456789abcdef0123456789abcdef0123456789";
    private static final String SESSION_ID = "3f2a9c11-0e4b-4a1f-9c3d-0b8e2f7a5d61";
    private static final String OTHER_SESSION_ID = "0b8e2f7a-5d61-4a1f-9c3d-3f2a9c110e4b";
    private static final String IPHONE_SAFARI = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) "
        + "Version/18.0 Mobile/15E148 Safari/604.1";

    private AuthWebUseCase authWebUseCase;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        authWebUseCase = mock(AuthWebUseCase.class);
        RefreshCookieProvider cookieProvider = new RefreshCookieProvider(new JwtAuthProperties(KEY, Duration.ofMinutes(15), KEY, Duration.ofDays(14)));
        mockMvc = MockMvcBuilders.standaloneSetup(new AuthWebController(authWebUseCase, new ClientIpResolver(), new DeviceLabelResolver(), cookieProvider))
            .setControllerAdvice(new AuthExceptionHandler(), new MemberExceptionHandler())
            .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
            // Spring Boot 기본 ObjectMapper 처럼 시각을 ISO-8601 문자열로 쓴다 (standalone 기본 변환기는 숫자 타임스탬프다).
            // 실제 앱의 ObjectMapper 가 그렇게 쓰는지는 AuthServiceApplicationTests 가 본다.
            .setMessageConverters(new MappingJackson2HttpMessageConverter(
                Jackson2ObjectMapperBuilder.json().featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build()))
            .build();
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("가입 성공은 200 성공 봉투이고, 요청 값을 그대로 명령으로 옮긴다(정규화는 Facade) — 건강정보 동의는 빠지면 미동의다")
    void signupSucceeds() throws Exception {
        postJson("/api/v1/auth/signup", VALID_SIGNUP)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dataHeader.success").value(true));

        ArgumentCaptor<AuthGeneralSignupCommand> command = ArgumentCaptor.forClass(AuthGeneralSignupCommand.class);
        verify(authWebUseCase).generalSignup(command.capture());
        assertThat(command.getValue().email()).isEqualTo("User@Example.com");
        assertThat(command.getValue().password()).isEqualTo("P@ssw0rd!");
        assertThat(command.getValue().nickname()).isEqualTo(" 재채기탐정 ");
        assertThat(command.getValue().termsAgreed()).isTrue();
        assertThat(command.getValue().privacyAgreed()).isTrue();
        assertThat(command.getValue().ageOver19Confirmed()).isTrue();
        assertThat(command.getValue().sensitiveHealthInfoAgreed()).isFalse();
    }

    @Test
    @DisplayName("건강정보 동의는 별도 필드로 받는다 — true 면 그대로 넘긴다")
    void signupForwardsHealthConsent() throws Exception {
        postJson("/api/v1/auth/signup", VALID_SIGNUP.replace("\"ageOver19Confirmed\":true", "\"ageOver19Confirmed\":true,\"sensitiveHealthInfoAgreed\":true"))
            .andExpect(status().isOk());

        ArgumentCaptor<AuthGeneralSignupCommand> command = ArgumentCaptor.forClass(AuthGeneralSignupCommand.class);
        verify(authWebUseCase).generalSignup(command.capture());
        assertThat(command.getValue().sensitiveHealthInfoAgreed()).isTrue();
    }

    @Test
    @DisplayName("필수 체크 셋이 빠지면 각각 AUTH_110 · 111 · 112 필드 오류이고, 유스케이스를 부르지 않는다")
    void missingRequiredChecksAreFieldErrors() throws Exception {
        postJson("/api/v1/auth/signup", """
            {"email":"user@example.com","password":"P@ssw0rd!","nickname":"재채기탐정"}""")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.dataHeader.success").value(false))
            .andExpect(jsonPath("$.dataHeader.resultCode").value("AUTH_110"))
            .andExpect(jsonPath("$.dataHeader.fieldErrors[0].field").value("termsAgreed"))
            .andExpect(jsonPath("$.dataHeader.fieldErrors[1].code").value("AUTH_111"))
            .andExpect(jsonPath("$.dataHeader.fieldErrors[2].code").value("AUTH_112"))
            .andExpect(jsonPath("$.dataHeader.fieldErrors.length()").value(3));
        verifyNoInteractions(authWebUseCase);
    }

    @Test
    @DisplayName("비밀번호 정책 위반은 길이(AUTH_106) · 구성(AUTH_107) 순으로, 필드 선언 순서대로 정렬된다")
    void passwordPolicyErrorsAreOrdered() throws Exception {
        postJson("/api/v1/auth/signup", VALID_SIGNUP.replace("P@ssw0rd!", "short"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("AUTH_106"))
            .andExpect(jsonPath("$.dataHeader.fieldErrors[0].field").value("password"))
            .andExpect(jsonPath("$.dataHeader.fieldErrors[1].code").value("AUTH_107"));

        postJson("/api/v1/auth/signup", VALID_SIGNUP.replace("P@ssw0rd!", "passwordonly"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("AUTH_107"));
        postJson("/api/v1/auth/signup", VALID_SIGNUP.replace("P@ssw0rd!", "12345678"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("AUTH_107"));
    }

    @Test
    @DisplayName("특수문자 없이 영문자 · 숫자만 있는 비밀번호도 받는다 (시안 Signup-account 규칙)")
    void passwordWithoutSpecialCharacterIsAccepted() throws Exception {
        postJson("/api/v1/auth/signup", VALID_SIGNUP.replace("P@ssw0rd!", "sneeze2026"))
            .andExpect(status().isOk());
    }

    @Test
    @DisplayName("이메일 형식 · 길이와 닉네임 길이 위반은 각자의 코드다")
    void emailAndNicknameErrors() throws Exception {
        postJson("/api/v1/auth/signup", VALID_SIGNUP.replace("User@Example.com", "not-an-email"))
            .andExpect(jsonPath("$.dataHeader.resultCode").value("AUTH_103"));
        postJson("/api/v1/auth/signup", VALID_SIGNUP.replace("User@Example.com", "a".repeat(95) + "@x.com"))
            .andExpect(jsonPath("$.dataHeader.resultCode").value("AUTH_102"));
        postJson("/api/v1/auth/signup", VALID_SIGNUP.replace(" 재채기탐정 ", "열한글자넘는닉네임입니다"))
            .andExpect(jsonPath("$.dataHeader.resultCode").value("AUTH_109"));
        postJson("/api/v1/auth/signup", VALID_SIGNUP.replace(" 재채기탐정 ", "탐"))
            .andExpect(jsonPath("$.dataHeader.resultCode").value("AUTH_109"));
    }

    @Test
    @DisplayName("깨진 JSON 은 AUTH_100 봉투다 — Spring 기본 400 이 아니다")
    void unreadableBodyIsEnveloped() throws Exception {
        postJson("/api/v1/auth/signup", "{\"email\":")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("AUTH_100"));
    }

    @Test
    @DisplayName("미인증 이메일 가입은 AUTH_007 봉투다")
    void unverifiedSignupIsEnveloped() throws Exception {
        doThrow(new AuthException(AuthErrorCode.EMAIL_NOT_VERIFIED)).when(authWebUseCase).generalSignup(any());

        postJson("/api/v1/auth/signup", VALID_SIGNUP)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("AUTH_007"));
    }

    @Test
    @DisplayName("중복 이메일은 MEMBER_001(409) 봉투다 — auth 컨트롤러에서 올라온 회원 예외도 봉투로 나간다")
    void duplicateEmailIsConflict() throws Exception {
        doThrow(new MemberException(MemberErrorCode.EXIST_MEMBER_EMAIL)).when(authWebUseCase).generalSignup(any());

        postJson("/api/v1/auth/signup", VALID_SIGNUP)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("MEMBER_001"));
    }

    @Test
    @DisplayName("발송 요청은 X-Real-IP 를 상한 키로 넘긴다 — 클라이언트가 바꿀 수 있는 X-Forwarded-For 는 보지 않는다")
    void sendCodeUsesRealIp() throws Exception {
        mockMvc.perform(post("/api/v1/auth/email/send-code").contentType(MediaType.APPLICATION_JSON)
                .header("X-Real-IP", "203.0.113.10").header("X-Forwarded-For", "198.51.100.1, 192.168.0.5")
                .content("{\"email\":\"user@example.com\"}"))
            .andExpect(status().isOk());

        verify(authWebUseCase).sendEmailVerificationCode("user@example.com", "203.0.113.10");
    }

    @Test
    @DisplayName("검증 요청도 X-Real-IP 를 IP 검증 상한 키로 넘긴다")
    void verifyCodeUsesRealIp() throws Exception {
        mockMvc.perform(post("/api/v1/auth/email/verify-code").contentType(MediaType.APPLICATION_JSON)
                .header("X-Real-IP", "203.0.113.10").content("{\"email\":\"user@example.com\",\"code\":\"482913\"}"))
            .andExpect(status().isOk());

        verify(authWebUseCase).verifyEmailVerificationCode("user@example.com", "482913", "203.0.113.10");
    }

    @Test
    @DisplayName("발송 상한 초과는 429 AUTH_002 봉투다")
    void sendCodeLimitIsTooManyRequests() throws Exception {
        doThrow(new AuthException(AuthErrorCode.EMAIL_SEND_IP_LIMITED)).when(authWebUseCase).sendEmailVerificationCode(any(), any());

        postJson("/api/v1/auth/email/send-code", "{\"email\":\"user@example.com\"}")
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("AUTH_002"));
    }

    @Test
    @DisplayName("검증 요청에 코드가 없으면 AUTH_104, 이메일이 없으면 AUTH_101 이다")
    void verifyCodeFieldErrors() throws Exception {
        postJson("/api/v1/auth/email/verify-code", "{\"email\":\"user@example.com\",\"code\":\" \"}")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("AUTH_104"));
        postJson("/api/v1/auth/email/verify-code", "{\"code\":\"482913\"}")
            .andExpect(jsonPath("$.dataHeader.resultCode").value("AUTH_101"));
        verifyNoInteractions(authWebUseCase);
    }

    @Test
    @DisplayName("로그인 성공은 본문에 access token 을, Set-Cookie 에 refresh 를 싣는다 — HttpOnly · Secure · SameSite=Strict · Path=/api/v1/auth · Max-Age")
    void loginSetsRefreshCookie() throws Exception {
        when(authWebUseCase.generalLogin(any())).thenReturn(AuthCookieResult.of(tokenResponse(), "refresh-token-value"));

        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .header("X-Real-IP", "203.0.113.10").header(HttpHeaders.USER_AGENT, IPHONE_SAFARI)
                .content("{\"email\":\"User@Example.com\",\"password\":\"a\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dataBody.memberId").value("1843956734582784"))
            .andExpect(jsonPath("$.dataBody.role").value("USER"))
            .andExpect(jsonPath("$.dataBody.accessToken").value("access-token"))
            .andExpect(jsonPath("$.dataBody.accessTokenExpiresIn").value(900))
            .andExpect(jsonPath("$.dataBody.pendingConsents[0]").value("PRIVACY_POLICY"))
            .andExpect(jsonPath("$.dataBody.reportWritable").value(false))
            .andExpect(jsonPath("$.dataBody.refreshToken").doesNotExist())
            .andExpect(header().string(HttpHeaders.SET_COOKIE, allOf(containsString("refreshToken=refresh-token-value"), containsString("HttpOnly"),
                containsString("Secure"), containsString("SameSite=Strict"), containsString("Path=/api/v1/auth"), containsString("Max-Age=1209600"))));

        ArgumentCaptor<AuthGeneralLoginCommand> command = ArgumentCaptor.forClass(AuthGeneralLoginCommand.class);
        verify(authWebUseCase).generalLogin(command.capture());
        assertThat(command.getValue().email()).isEqualTo("User@Example.com");
        assertThat(command.getValue().password()).as("로그인은 가입 비밀번호 규칙을 걸지 않는다").isEqualTo("a");
        assertThat(command.getValue().clientIp()).isEqualTo("203.0.113.10");
        assertThat(command.getValue().deviceLabel()).as("User-Agent 원문이 아니라 줄인 이름만 넘긴다").isEqualTo("iPhone · Safari");
    }

    @Test
    @DisplayName("로그인 검증 오류 — 비밀번호 누락 AUTH_105 · 100자 초과 AUTH_113, 이메일 형식 AUTH_103 · 누락 AUTH_101")
    void loginValidationErrors() throws Exception {
        postJson("/api/v1/auth/login", "{\"email\":\"user@example.com\",\"password\":\" \"}")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("AUTH_105"));
        postJson("/api/v1/auth/login", "{\"email\":\"user@example.com\",\"password\":\"" + "a".repeat(101) + "\"}")
            .andExpect(jsonPath("$.dataHeader.resultCode").value("AUTH_113"));
        postJson("/api/v1/auth/login", "{\"email\":\"not-an-email\",\"password\":\"P@ssw0rd!\"}")
            .andExpect(jsonPath("$.dataHeader.resultCode").value("AUTH_103"));
        postJson("/api/v1/auth/login", "{\"password\":\"P@ssw0rd!\"}")
            .andExpect(jsonPath("$.dataHeader.resultCode").value("AUTH_101"));
        verifyNoInteractions(authWebUseCase);
    }

    @Test
    @DisplayName("로그인 실패는 401 AUTH_011, 잠금은 429 AUTH_012, 정지 회원은 403 MEMBER_003 봉투이고 쿠키를 굽지 않는다")
    void loginFailuresAreEnveloped() throws Exception {
        doThrow(new AuthException(AuthErrorCode.LOGIN_FAILED)).when(authWebUseCase).generalLogin(any());
        postJson("/api/v1/auth/login", "{\"email\":\"user@example.com\",\"password\":\"wrong\"}")
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("AUTH_011"))
            .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));

        doThrow(new AuthException(AuthErrorCode.LOGIN_ATTEMPT_LOCKED)).when(authWebUseCase).generalLogin(any());
        postJson("/api/v1/auth/login", "{\"email\":\"user@example.com\",\"password\":\"wrong\"}")
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("AUTH_012"));

        doThrow(new MemberException(MemberErrorCode.SUSPENDED_MEMBER)).when(authWebUseCase).generalLogin(any());
        postJson("/api/v1/auth/login", "{\"email\":\"user@example.com\",\"password\":\"right\"}")
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("MEMBER_003"));
    }

    @Test
    @DisplayName("재발급은 refreshToken 쿠키 값을 넘기고 회전한 refresh 를 다시 Set-Cookie 로 굽는다")
    void reissueRotatesCookie() throws Exception {
        when(authWebUseCase.reissueToken("old-refresh")).thenReturn(AuthCookieResult.of(tokenResponse(), "new-refresh"));

        mockMvc.perform(post("/api/v1/auth/token/reissue").cookie(new Cookie(RefreshCookieProvider.REFRESH_TOKEN_COOKIE, "old-refresh")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dataBody.accessToken").value("access-token"))
            .andExpect(header().string(HttpHeaders.SET_COOKIE, allOf(containsString("refreshToken=new-refresh"), containsString("HttpOnly"),
                containsString("Secure"), containsString("SameSite=Strict"), containsString("Path=/api/v1/auth"))));
    }

    @Test
    @DisplayName("쿠키 없는 재발급은 null 을 넘기고, 유스케이스의 AUTH_014 가 401 봉투로 나간다 · 동시 재발급 경합은 409 AUTH_016")
    void reissueErrorsAreEnveloped() throws Exception {
        when(authWebUseCase.reissueToken(null)).thenThrow(new AuthException(AuthErrorCode.REFRESH_TOKEN_EXPIRED));
        mockMvc.perform(post("/api/v1/auth/token/reissue"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("AUTH_014"))
            .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));

        doThrow(new AuthException(AuthErrorCode.REFRESH_TOKEN_ROTATED)).when(authWebUseCase).reissueToken("stale");
        mockMvc.perform(post("/api/v1/auth/token/reissue").cookie(new Cookie(RefreshCookieProvider.REFRESH_TOKEN_COOKIE, "stale")))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("AUTH_016"));
    }

    @Test
    @DisplayName("로그아웃은 주체의 세션 · jti · 만료를 넘기고 refresh 쿠키를 Max-Age=0 으로 지운다")
    void logoutClearsCookie() throws Exception {
        Instant expiresAt = Instant.parse("2026-10-01T05:15:00Z");
        authenticate(SESSION_ID, expiresAt);

        mockMvc.perform(post("/api/v1/auth/logout"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dataHeader.success").value(true))
            .andExpect(header().string(HttpHeaders.SET_COOKIE, allOf(containsString("refreshToken=;"), containsString("Max-Age=0"),
                containsString("Path=/api/v1/auth"), containsString("HttpOnly"), containsString("Secure"), containsString("SameSite=Strict"))));

        verify(authWebUseCase).logout(42L, SESSION_ID, "access-jti", expiresAt);
    }

    @Test
    @DisplayName("기기 목록은 주체의 세션을 현재 세션으로 넘기고, 시각은 ISO-8601 UTC 문자열이다")
    void listsSessions() throws Exception {
        authenticate(SESSION_ID, null);
        when(authWebUseCase.getSessions(42L, SESSION_ID)).thenReturn(AuthSessionsResponse.builder()
            .sessions(List.of(AuthSessionItem.builder().sessionId(SESSION_ID).deviceLabel("iPhone · Safari")
                .createdAt(Instant.parse("2026-10-01T00:30:00Z")).lastUsedAt(Instant.parse("2026-10-01T05:12:00Z")).current(true).build()))
            .totalCount(1)
            .build());

        mockMvc.perform(get("/api/v1/auth/sessions"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dataBody.totalCount").value(1))
            .andExpect(jsonPath("$.dataBody.sessions[0].sessionId").value(SESSION_ID))
            .andExpect(jsonPath("$.dataBody.sessions[0].deviceLabel").value("iPhone · Safari"))
            .andExpect(jsonPath("$.dataBody.sessions[0].createdAt").value("2026-10-01T00:30:00Z"))
            .andExpect(jsonPath("$.dataBody.sessions[0].lastUsedAt").value("2026-10-01T05:12:00Z"))
            .andExpect(jsonPath("$.dataBody.sessions[0].current").value(true));
    }

    @Test
    @DisplayName("현재 기기 세션을 지우면 쿠키도 지우고, 다른 기기 세션이면 쿠키를 건드리지 않는다")
    void revokeSessionClearsCookieOnlyForCurrent() throws Exception {
        authenticate(SESSION_ID, null);

        mockMvc.perform(delete("/api/v1/auth/sessions/" + SESSION_ID))
            .andExpect(status().isOk())
            .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")));
        mockMvc.perform(delete("/api/v1/auth/sessions/" + OTHER_SESSION_ID))
            .andExpect(status().isOk())
            .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));

        verify(authWebUseCase).revokeSession(42L, SESSION_ID);
        verify(authWebUseCase).revokeSession(42L, OTHER_SESSION_ID);
    }

    @Test
    @DisplayName("세션 아이디가 UUID 형식이 아니면 AUTH_114 이고 유스케이스를 부르지 않는다 — 임의 값을 Redis 키에 넣지 않는다")
    void revokeSessionRejectsMalformedId() throws Exception {
        authenticate(SESSION_ID, null);

        mockMvc.perform(delete("/api/v1/auth/sessions/not-a-session"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("AUTH_114"));
        verifyNoInteractions(authWebUseCase);
    }

    @Test
    @DisplayName("다른 기기 모두 로그아웃은 현재 세션을 남길 세션으로 넘긴다")
    void revokeOtherSessions() throws Exception {
        authenticate(SESSION_ID, null);

        mockMvc.perform(delete("/api/v1/auth/sessions"))
            .andExpect(status().isOk())
            .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));

        verify(authWebUseCase).revokeOtherSessions(42L, SESSION_ID);
    }

    private static AuthTokenResponse tokenResponse() {
        return AuthTokenResponse.builder().memberId("1843956734582784").role("USER").accessToken("access-token").accessTokenExpiresIn(900)
            .pendingConsents(List.of("PRIVACY_POLICY")).reportWritable(false).build();
    }

    private static void authenticate(String sessionId, Instant expiresAt) {
        MemberLoginActive principal = MemberLoginActive.builder().memberId(42L).role(SecurityRole.USER).tokenId("access-jti").expiresAt(expiresAt)
            .sessionId(sessionId).build();
        SecurityContextHolder.getContext().setAuthentication(JwtAuthentication.authenticated(principal));
    }

    private ResultActions postJson(String path, String body) throws Exception {
        return mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
