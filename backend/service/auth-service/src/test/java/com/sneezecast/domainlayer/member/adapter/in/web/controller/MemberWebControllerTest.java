package com.sneezecast.domainlayer.member.adapter.in.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sneezecast.domainlayer.member.adapter.in.web.dto.response.MemberConsentStatusResponse;
import com.sneezecast.domainlayer.member.adapter.in.web.dto.response.MemberMyInfoResponse;
import com.sneezecast.domainlayer.member.adapter.in.web.dto.response.MemberProfileImageResponse;
import com.sneezecast.domainlayer.member.adapter.in.web.exception.MemberExceptionHandler;
import com.sneezecast.domainlayer.member.adapter.in.web.exception.MemberRequestExceptionHandler;
import com.sneezecast.domainlayer.member.adapter.in.web.exception.MemberStorageExceptionHandler;
import com.sneezecast.domainlayer.member.application.exception.MemberErrorCode;
import com.sneezecast.domainlayer.member.application.exception.MemberException;
import com.sneezecast.domainlayer.member.application.port.in.MemberConsentWithdrawResult;
import com.sneezecast.domainlayer.member.application.port.in.MemberWebUseCase;
import com.sneezecast.domainlayer.member.domain.enums.ConsentType;
import com.sneezecast.security.common.dto.MemberLoginActive;
import com.sneezecast.security.common.enums.SecurityRole;
import com.sneezecast.security.common.jwt.JwtAuthentication;
import com.sneezecast.storage.exception.StorageErrorCode;
import com.sneezecast.storage.exception.StorageException;
import com.sneezecast.storage.model.FileUploadCommand;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
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
            .setControllerAdvice(new MemberRequestExceptionHandler(), new MemberStorageExceptionHandler(), new MemberExceptionHandler())
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
    @DisplayName("프로필 이미지 업로드는 multipart 파트 imageFile 의 내용 · 파일명을 유스케이스에 넘기고 공개 URL 을 돌려준다")
    void changeProfileImage() throws Exception {
        byte[] content = {(byte) 0x89, 'P', 'N', 'G'};
        when(memberWebUseCase.changeProfileImage(eq(42L), any())).thenReturn(new MemberProfileImageResponse("https://minio.example.com/sneezecast/k.png"));

        mockMvc.perform(multipart("/api/v1/members/me/profile-image").file(new MockMultipartFile("imageFile", "me.png", "image/png", content)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dataHeader.success").value(true))
            .andExpect(jsonPath("$.dataBody.profileImageUrl").value("https://minio.example.com/sneezecast/k.png"));

        ArgumentCaptor<FileUploadCommand> command = ArgumentCaptor.forClass(FileUploadCommand.class);
        verify(memberWebUseCase).changeProfileImage(eq(42L), command.capture());
        assertThat(command.getValue().content()).isEqualTo(content);
        assertThat(command.getValue().originalFilename()).isEqualTo("me.png");
    }

    @Test
    @DisplayName("파트가 없거나 빈 파일이면 유스케이스를 부르지 않고 400 STORAGE_001 봉투다")
    void missingOrEmptyProfileImageIsStorage001() throws Exception {
        mockMvc.perform(multipart("/api/v1/members/me/profile-image"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("STORAGE_001"));
        mockMvc.perform(multipart("/api/v1/members/me/profile-image").file(new MockMultipartFile("imageFile", "me.png", "image/png", new byte[0])))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("STORAGE_001"));
        mockMvc.perform(multipart("/api/v1/members/me/profile-image").file(new MockMultipartFile("file", "me.png", "image/png", new byte[] {1})))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("STORAGE_001"));

        verifyNoInteractions(memberWebUseCase);
    }

    @Test
    @DisplayName("스토리지 예외는 STORAGE 봉투다 — 형식 400 STORAGE_003 · 저장소 장애 500 STORAGE_004")
    void storageErrorsAreEnveloped() throws Exception {
        when(memberWebUseCase.changeProfileImage(eq(42L), any()))
            .thenThrow(new StorageException(StorageErrorCode.UNSUPPORTED_FILE_TYPE))
            .thenThrow(new StorageException(StorageErrorCode.FILE_UPLOAD_FAILED));
        MockMultipartFile file = new MockMultipartFile("imageFile", "me.png", "image/png", new byte[] {1});

        mockMvc.perform(multipart("/api/v1/members/me/profile-image").file(file))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.dataHeader.success").value(false))
            .andExpect(jsonPath("$.dataHeader.resultCode").value("STORAGE_003"));
        mockMvc.perform(multipart("/api/v1/members/me/profile-image").file(file))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("STORAGE_004"));
    }

    @Test
    @DisplayName("프로필 이미지 삭제는 주체의 회원 ID 로 지우고 200 · dataBody null 이다")
    void removeProfileImage() throws Exception {
        mockMvc.perform(delete("/api/v1/members/me/profile-image"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dataHeader.success").value(true))
            .andExpect(jsonPath("$.dataBody").value(nullValue()));

        verify(memberWebUseCase).removeProfileImage(42L);
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

    @Test
    @DisplayName("동의는 주체의 회원 ID · 항목 · 문서 버전을 넘기고 동의 뒤 상태를 싣는다 — 쿠키는 건드리지 않는다")
    void agreeConsent() throws Exception {
        when(memberWebUseCase.agreeConsent(42L, ConsentType.SENSITIVE_HEALTH_INFO, "2026-10-01")).thenReturn(consentStatus(true, true, false));

        sendJson(post("/api/v1/members/me/consents"), "{\"type\":\"SENSITIVE_HEALTH_INFO\",\"documentVersion\":\"2026-10-01\"}")
            .andExpect(status().isOk())
            .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
            .andExpect(jsonPath("$.dataBody.pendingConsents.length()").value(0))
            .andExpect(jsonPath("$.dataBody.healthInfoAgreed").value(true))
            .andExpect(jsonPath("$.dataBody.reportWritable").value(true))
            .andExpect(jsonPath("$.dataBody.purgePending").value(false));
    }

    @Test
    @DisplayName("동의 검증 — 항목 누락 MEMBER_108, 문서 버전 누락 · 공백 MEMBER_109 · 20자 초과 MEMBER_110, 모르는 항목 값 · 깨진 JSON MEMBER_100")
    void agreeConsentValidation() throws Exception {
        String path = "/api/v1/members/me/consents";
        sendJson(post(path), "{\"documentVersion\":\"2026-10-01\"}")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("MEMBER_108"));
        sendJson(post(path), "{\"type\":\"TERMS_OF_SERVICE\"}")
            .andExpect(jsonPath("$.dataHeader.resultCode").value("MEMBER_109"));
        sendJson(post(path), "{\"type\":\"TERMS_OF_SERVICE\",\"documentVersion\":\"  \"}")
            .andExpect(jsonPath("$.dataHeader.resultCode").value("MEMBER_109"));
        sendJson(post(path), "{\"type\":\"TERMS_OF_SERVICE\",\"documentVersion\":\"" + "v".repeat(21) + "\"}")
            .andExpect(jsonPath("$.dataHeader.resultCode").value("MEMBER_110"))
            .andExpect(jsonPath("$.dataHeader.fieldErrors[0].field").value("documentVersion"));
        sendJson(post(path), "{\"type\":\"PUSH_NOTIFICATION\",\"documentVersion\":\"2026-10-01\"}")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("MEMBER_100"))
            .andExpect(jsonPath("$.dataHeader.fieldErrors[0].field").value("type"));
        sendJson(post(path), "{\"type\":")
            .andExpect(jsonPath("$.dataHeader.resultCode").value("MEMBER_100"));
        verifyNoInteractions(memberWebUseCase);
    }

    @Test
    @DisplayName("동의 실패는 MEMBER 봉투다 — 받지 않는 항목 400 MEMBER_010 · 문서 버전 불일치 409 MEMBER_011")
    void agreeConsentFailuresAreEnveloped() throws Exception {
        for (MemberErrorCode code : List.of(MemberErrorCode.CONSENT_NOT_AGREEABLE, MemberErrorCode.CONSENT_VERSION_MISMATCH)) {
            doThrow(new MemberException(code)).when(memberWebUseCase).agreeConsent(anyLong(), any(), any());
            sendJson(post("/api/v1/members/me/consents"), "{\"type\":\"AGE_OVER_19\",\"documentVersion\":\"2026-10-01\"}")
                .andExpect(status().is(code.getHttpStatus().value()))
                .andExpect(jsonPath("$.dataHeader.resultCode").value(code.getCode()));
        }
    }

    @Test
    @DisplayName("철회하면 주체의 access jti 를 넘기고, 로그아웃했으면 refresh 쿠키를 지운다(auth 와 같은 이름 · Path · 속성, Max-Age=0)")
    void withdrawConsentClearsRefreshCookie() throws Exception {
        when(memberWebUseCase.withdrawConsent(42L, ConsentType.SENSITIVE_HEALTH_INFO, "access-jti", null))
            .thenReturn(new MemberConsentWithdrawResult(consentStatus(false, false, true), true));

        mockMvc.perform(delete("/api/v1/members/me/consents/SENSITIVE_HEALTH_INFO"))
            .andExpect(status().isOk())
            .andExpect(header().string(HttpHeaders.SET_COOKIE, allOf(startsWith("refreshToken=;"), containsString("Max-Age=0"),
                containsString("Path=/api/v1/auth"), containsString("HttpOnly"), containsString("Secure"), containsString("SameSite=Strict"))))
            .andExpect(jsonPath("$.dataBody.healthInfoAgreed").value(false))
            .andExpect(jsonPath("$.dataBody.reportWritable").value(false))
            .andExpect(jsonPath("$.dataBody.purgePending").value(true));
    }

    @Test
    @DisplayName("철회할 동의가 없어 아무것도 하지 않았으면(멱등) 200 이고 쿠키를 지우지 않는다")
    void idempotentWithdrawKeepsCookie() throws Exception {
        when(memberWebUseCase.withdrawConsent(42L, ConsentType.SENSITIVE_HEALTH_INFO, "access-jti", null))
            .thenReturn(new MemberConsentWithdrawResult(consentStatus(false, false, false), false));

        mockMvc.perform(delete("/api/v1/members/me/consents/SENSITIVE_HEALTH_INFO"))
            .andExpect(status().isOk())
            .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
    }

    @Test
    @DisplayName("철회 경로 — 철회할 수 없는 항목은 400 MEMBER_012, 모르는 항목 값은 400 MEMBER_198 이다")
    void withdrawConsentErrors() throws Exception {
        when(memberWebUseCase.withdrawConsent(anyLong(), eq(ConsentType.TERMS_OF_SERVICE), any(), any()))
            .thenThrow(new MemberException(MemberErrorCode.CONSENT_NOT_WITHDRAWABLE));
        mockMvc.perform(delete("/api/v1/members/me/consents/TERMS_OF_SERVICE"))
            .andExpect(status().isBadRequest())
            .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
            .andExpect(jsonPath("$.dataHeader.resultCode").value("MEMBER_012"));

        mockMvc.perform(delete("/api/v1/members/me/consents/HEALTH"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("MEMBER_198"));
    }

    @Test
    @DisplayName("탈퇴하면 주체의 access jti · 만료를 넘기고, 본문 null 로 200 이며 refresh 쿠키를 지운다(auth 와 같은 이름 · Path · 속성, Max-Age=0)")
    void withdrawClearsRefreshCookie() throws Exception {
        mockMvc.perform(post("/api/v1/members/me/withdraw"))
            .andExpect(status().isOk())
            .andExpect(header().string(HttpHeaders.SET_COOKIE, allOf(startsWith("refreshToken=;"), containsString("Max-Age=0"),
                containsString("Path=/api/v1/auth"), containsString("HttpOnly"), containsString("Secure"), containsString("SameSite=Strict"))))
            .andExpect(jsonPath("$.dataHeader.success").value(true))
            .andExpect(jsonPath("$.dataBody").value(nullValue()));

        verify(memberWebUseCase).withdraw(42L, "access-jti", null);
    }

    @Test
    @DisplayName("이미 탈퇴한 회원의 남은 access 면 403 MEMBER_002 봉투이고 쿠키를 건드리지 않는다 — 회원 없음은 404 MEMBER_004")
    void withdrawErrorsAreEnveloped() throws Exception {
        doThrow(new MemberException(MemberErrorCode.WITHDRAWN_MEMBER)).when(memberWebUseCase).withdraw(anyLong(), any(), any());
        mockMvc.perform(post("/api/v1/members/me/withdraw"))
            .andExpect(status().isForbidden())
            .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
            .andExpect(jsonPath("$.dataHeader.resultCode").value("MEMBER_002"));

        doThrow(new MemberException(MemberErrorCode.MEMBER_NOT_FOUND)).when(memberWebUseCase).withdraw(anyLong(), any(), any());
        mockMvc.perform(post("/api/v1/members/me/withdraw"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("MEMBER_004"));
    }

    private static MemberConsentStatusResponse consentStatus(boolean healthInfoAgreed, boolean reportWritable, boolean purgePending) {
        return MemberConsentStatusResponse.builder().pendingConsents(List.of()).healthInfoAgreed(healthInfoAgreed).reportWritable(reportWritable)
            .purgePending(purgePending).build();
    }

    private static MemberMyInfoResponse myInfo(String nickname) {
        return MemberMyInfoResponse.builder().memberId("42").email("user@example.com").nickname(nickname).provider("KAKAO").hasPassword(false)
            .role("USER").pendingConsents(List.of("PRIVACY_POLICY")).reportWritable(false).build();
    }

    private ResultActions sendJson(MockHttpServletRequestBuilder request, String body) throws Exception {
        return mockMvc.perform(request.contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
