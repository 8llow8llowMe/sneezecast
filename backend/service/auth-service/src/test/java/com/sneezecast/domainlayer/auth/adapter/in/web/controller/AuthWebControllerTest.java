package com.sneezecast.domainlayer.auth.adapter.in.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sneezecast.domainlayer.auth.adapter.in.web.exception.AuthExceptionHandler;
import com.sneezecast.domainlayer.auth.adapter.in.web.support.ClientIpResolver;
import com.sneezecast.domainlayer.auth.application.command.AuthGeneralSignupCommand;
import com.sneezecast.domainlayer.auth.application.exception.AuthErrorCode;
import com.sneezecast.domainlayer.auth.application.exception.AuthException;
import com.sneezecast.domainlayer.auth.application.port.in.AuthWebUseCase;
import com.sneezecast.domainlayer.member.adapter.in.web.exception.MemberExceptionHandler;
import com.sneezecast.domainlayer.member.application.exception.MemberErrorCode;
import com.sneezecast.domainlayer.member.application.exception.MemberException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 요청 검증 · 오류 봉투 · 필드별 코드를 실제 MVC 경로(Bean Validation + advice)로 본다. 유스케이스는 mock 이다.
 */
class AuthWebControllerTest {

    private static final String VALID_SIGNUP = """
        {"email":"User@Example.com","password":"P@ssw0rd!","nickname":" 재채기탐정 ",
         "termsAgreed":true,"privacyAgreed":true,"ageOver19Confirmed":true}""";

    private AuthWebUseCase authWebUseCase;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        authWebUseCase = mock(AuthWebUseCase.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new AuthWebController(authWebUseCase, new ClientIpResolver()))
            .setControllerAdvice(new AuthExceptionHandler(), new MemberExceptionHandler())
            .build();
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

        postJson("/api/v1/auth/signup", VALID_SIGNUP.replace("P@ssw0rd!", "password1234"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("AUTH_107"));
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
                .header("X-Real-IP", "203.0.113.10").content("{\"email\":\"user@example.com\",\"code\":\"ABCD2345\"}"))
            .andExpect(status().isOk());

        verify(authWebUseCase).verifyEmailVerificationCode("user@example.com", "ABCD2345", "203.0.113.10");
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
        postJson("/api/v1/auth/email/verify-code", "{\"code\":\"ABCD2345\"}")
            .andExpect(jsonPath("$.dataHeader.resultCode").value("AUTH_101"));
        verifyNoInteractions(authWebUseCase);
    }

    private ResultActions postJson(String path, String body) throws Exception {
        return mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
