package com.sneezecast.domainlayer.member.adapter.in.web.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sneezecast.domainlayer.member.adapter.in.web.dto.response.MemberMyInfoResponse;
import com.sneezecast.domainlayer.member.adapter.in.web.exception.MemberExceptionHandler;
import com.sneezecast.domainlayer.member.adapter.in.web.exception.MemberRequestExceptionHandler;
import com.sneezecast.domainlayer.member.application.exception.MemberErrorCode;
import com.sneezecast.domainlayer.member.application.exception.MemberException;
import com.sneezecast.domainlayer.member.application.port.in.MemberWebUseCase;
import com.sneezecast.security.common.dto.MemberLoginActive;
import com.sneezecast.security.common.enums.SecurityRole;
import com.sneezecast.security.common.jwt.JwtAuthentication;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 요청 검증 · 오류 봉투 · 필드별 코드를 실제 MVC 경로(Bean Validation + advice)로 본다. 유스케이스는 mock 이다.
 * 인증 주체는 SecurityContext 에 직접 세운다 — 토큰이 없을 때 401 인지는 {@code AuthServiceApplicationTests} 가 실제 필터 체인으로 본다.
 */
class MemberWebControllerTest {

    private static final String SESSION_ID = "3f2a9c11-0e4b-4a1f-9c3d-0b8e2f7a5d61";

    private MemberWebUseCase memberWebUseCase;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        memberWebUseCase = mock(MemberWebUseCase.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new MemberWebController(memberWebUseCase))
            .setControllerAdvice(new MemberRequestExceptionHandler(), new MemberExceptionHandler())
            .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
            .build();
        MemberLoginActive principal = MemberLoginActive.builder().memberId(42L).role(SecurityRole.USER).tokenId("access-jti").sessionId(SESSION_ID)
            .build();
        SecurityContextHolder.getContext().setAuthentication(JwtAuthentication.authenticated(principal));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("내 정보는 주체의 회원 ID 로 조회하고 응답 필드를 그대로 싣는다")
    void getMyInfo() throws Exception {
        when(memberWebUseCase.getMyInfo(42L)).thenReturn(myInfo("재채기탐정"));

        mockMvc.perform(get("/api/v1/members/me"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dataBody.memberId").value("42"))
            .andExpect(jsonPath("$.dataBody.email").value("user@example.com"))
            .andExpect(jsonPath("$.dataBody.nickname").value("재채기탐정"))
            .andExpect(jsonPath("$.dataBody.provider").value("KAKAO"))
            .andExpect(jsonPath("$.dataBody.hasPassword").value(false))
            .andExpect(jsonPath("$.dataBody.role").value("USER"))
            .andExpect(jsonPath("$.dataBody.pendingConsents[0]").value("PRIVACY_POLICY"))
            .andExpect(jsonPath("$.dataBody.reportWritable").value(false));
    }

    @Test
    @DisplayName("회원 예외는 MEMBER 봉투다 — 없음 404 MEMBER_004 · 정지 403 MEMBER_003")
    void memberErrorsAreEnveloped() throws Exception {
        when(memberWebUseCase.getMyInfo(42L))
            .thenThrow(new MemberException(MemberErrorCode.MEMBER_NOT_FOUND))
            .thenThrow(new MemberException(MemberErrorCode.SUSPENDED_MEMBER));
        mockMvc.perform(get("/api/v1/members/me"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("MEMBER_004"));
        mockMvc.perform(get("/api/v1/members/me"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("MEMBER_003"));
    }

    @Test
    @DisplayName("닉네임 수정은 값을 그대로 넘기고(공백 정리는 Facade) 바뀐 내 정보를 돌려준다")
    void updateMyInfo() throws Exception {
        when(memberWebUseCase.updateMyInfo(42L, " 새닉네임 ")).thenReturn(myInfo("새닉네임"));

        sendJson(patch("/api/v1/members/me"), "{\"nickname\":\" 새닉네임 \"}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dataBody.nickname").value("새닉네임"));
    }

    @Test
    @DisplayName("닉네임 검증 — 누락 · 공백만 MEMBER_101, 길이 MEMBER_102, 깨진 JSON MEMBER_100 이고 유스케이스를 부르지 않는다")
    void nicknameValidation() throws Exception {
        sendJson(patch("/api/v1/members/me"), "{}")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("MEMBER_101"));
        sendJson(patch("/api/v1/members/me"), "{\"nickname\":\"  \"}")
            .andExpect(jsonPath("$.dataHeader.resultCode").value("MEMBER_101"))
            .andExpect(jsonPath("$.dataHeader.fieldErrors.length()").value(1));
        sendJson(patch("/api/v1/members/me"), "{\"nickname\":\"탐\"}")
            .andExpect(jsonPath("$.dataHeader.resultCode").value("MEMBER_102"));
        // 길이는 앞뒤 공백을 지운 값(저장값)으로 잰다 — 원문 3자라도 지우면 1자다.
        sendJson(patch("/api/v1/members/me"), "{\"nickname\":\" 가 \"}")
            .andExpect(jsonPath("$.dataHeader.resultCode").value("MEMBER_102"))
            .andExpect(jsonPath("$.dataHeader.fieldErrors[0].field").value("nickname"))
            .andExpect(jsonPath("$.dataHeader.fieldErrors.length()").value(1));
        sendJson(patch("/api/v1/members/me"), "{\"nickname\":\"열한글자넘는닉네임입니다\"}")
            .andExpect(jsonPath("$.dataHeader.resultCode").value("MEMBER_102"));
        sendJson(patch("/api/v1/members/me"), "{\"nickname\":")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("MEMBER_100"));
        verifyNoInteractions(memberWebUseCase);
    }

    @Test
    @DisplayName("비밀번호 변경은 주체의 회원 ID · 세션 ID 와 두 비밀번호를 넘긴다")
    void changePassword() throws Exception {
        sendJson(post("/api/v1/members/me/password"), "{\"currentPassword\":\"old\",\"newPassword\":\"Sneeze2026!\"}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dataHeader.success").value(true));

        verify(memberWebUseCase).changePassword(42L, SESSION_ID, "old", "Sneeze2026!");
    }

    @Test
    @DisplayName("비밀번호 변경 검증 — 현재 누락 MEMBER_103 · 100자 초과 MEMBER_104, 새 비밀번호 누락 MEMBER_105 · 길이 MEMBER_106 · 구성 MEMBER_107")
    void changePasswordValidation() throws Exception {
        String path = "/api/v1/members/me/password";
        sendJson(post(path), "{\"newPassword\":\"Sneeze2026!\"}")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("MEMBER_103"));
        sendJson(post(path), "{\"currentPassword\":\"" + "a".repeat(101) + "\",\"newPassword\":\"Sneeze2026!\"}")
            .andExpect(jsonPath("$.dataHeader.resultCode").value("MEMBER_104"));
        sendJson(post(path), "{\"currentPassword\":\"old\"}")
            .andExpect(jsonPath("$.dataHeader.resultCode").value("MEMBER_105"));
        sendJson(post(path), "{\"currentPassword\":\"old\",\"newPassword\":\"short1\"}")
            .andExpect(jsonPath("$.dataHeader.resultCode").value("MEMBER_106"))
            .andExpect(jsonPath("$.dataHeader.fieldErrors[0].field").value("newPassword"));
        sendJson(post(path), "{\"currentPassword\":\"old\",\"newPassword\":\"passwordonly\"}")
            .andExpect(jsonPath("$.dataHeader.resultCode").value("MEMBER_107"));
        sendJson(post(path), "{\"currentPassword\":\"old\",\"newPassword\":\"pass word1\"}")
            .andExpect(jsonPath("$.dataHeader.resultCode").value("MEMBER_107"));
        verifyNoInteractions(memberWebUseCase);
    }

    @Test
    @DisplayName("비밀번호 변경 실패는 MEMBER 봉투다 — 틀림 400 MEMBER_005 · 잠금 429 MEMBER_006 · 소셜 409 MEMBER_007 · 세션 폐기 실패 503 MEMBER_009")
    void changePasswordFailuresAreEnveloped() throws Exception {
        String body = "{\"currentPassword\":\"old\",\"newPassword\":\"Sneeze2026!\"}";
        for (MemberErrorCode code : List.of(MemberErrorCode.CURRENT_PASSWORD_MISMATCH, MemberErrorCode.PASSWORD_CHANGE_LOCKED,
            MemberErrorCode.PASSWORD_NOT_SET, MemberErrorCode.SESSION_REVOKE_UNAVAILABLE)) {
            doThrow(new MemberException(code)).when(memberWebUseCase).changePassword(anyLong(), any(), any(), any());
            sendJson(post("/api/v1/members/me/password"), body)
                .andExpect(status().is(code.getHttpStatus().value()))
                .andExpect(jsonPath("$.dataHeader.resultCode").value(code.getCode()));
        }
    }

    @Test
    @DisplayName("비밀번호 최초 설정 API 는 없다(#61 — 카카오 회원은 비밀번호가 필요 없다) — 경로가 매핑되지 않아 404 이고 유스케이스를 부르지 않는다")
    void passwordSetupEndpointIsGone() throws Exception {
        sendJson(post("/api/v1/members/me/password/setup"), "{\"newPassword\":\"Sneeze2026!\"}")
            .andExpect(status().isNotFound());
        verifyNoInteractions(memberWebUseCase);
    }

    private static MemberMyInfoResponse myInfo(String nickname) {
        return MemberMyInfoResponse.builder().memberId("42").email("user@example.com").nickname(nickname).provider("KAKAO").hasPassword(false)
            .role("USER").pendingConsents(List.of("PRIVACY_POLICY")).reportWritable(false).build();
    }

    private ResultActions sendJson(MockHttpServletRequestBuilder request, String body) throws Exception {
        return mockMvc.perform(request.contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
