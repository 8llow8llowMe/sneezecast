package com.sneezecast.domainlayer.member.adapter.in.web.controller;

import com.sneezecast.common.dto.Response;
import com.sneezecast.domainlayer.member.adapter.in.web.dto.request.MemberConsentAgreeRequest;
import com.sneezecast.domainlayer.member.adapter.in.web.dto.request.MemberMyInfoUpdateRequest;
import com.sneezecast.domainlayer.member.adapter.in.web.dto.request.MemberPasswordChangeRequest;
import com.sneezecast.domainlayer.member.adapter.in.web.dto.response.MemberConsentStatusResponse;
import com.sneezecast.domainlayer.member.adapter.in.web.dto.response.MemberMyInfoResponse;
import com.sneezecast.domainlayer.member.adapter.in.web.support.MemberRefreshCookie;
import com.sneezecast.domainlayer.member.application.port.in.MemberConsentWithdrawResult;
import com.sneezecast.domainlayer.member.application.port.in.MemberWebUseCase;
import com.sneezecast.domainlayer.member.domain.enums.ConsentType;
import com.sneezecast.security.common.dto.MemberLoginActive;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/members")
@Tag(name = "회원", description = "내 정보 조회 · 수정, 비밀번호 변경, 동의 · 철회 API")
public class MemberWebController {

    private final MemberWebUseCase memberWebUseCase;

    @Operation(summary = "내 정보 조회", description = """
        로그인한 회원의 내 정보를 돌려줍니다 — 이메일, 닉네임, 로그인 방법(`provider`: EMAIL · KAKAO — 카카오를 연결한 이메일 계정도 KAKAO,
        비밀번호 로그인 가능 여부는 `hasPassword`), 비밀번호가 있는지(`hasPassword`), 역할.
        `hasPassword` 가 true 면 비밀번호 변경을 보여 주고, false 면(카카오로만 로그인하는 계정) 비밀번호 메뉴를 보여 주지 않습니다 — 카카오 회원은
        비밀번호가 필요 없습니다.
        `pendingConsents` · `reportWritable` 은 로그인 · 재발급 응답과 같은 계산입니다. 내 동네 · 프로필 이미지는 아직 싣지 않습니다.

        회원 행이 없으면 MEMBER_004(404), 탈퇴 MEMBER_002 · 정지 MEMBER_003(403).

        **인증 필요: Authorization 헤더.** 파라미터는 없습니다. 토큰이 없으면 SECURITY_001(401).

        호출 예: `GET /api/v1/members/me`""",
        security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Response<MemberMyInfoResponse>> getMyInfo(@AuthenticationPrincipal MemberLoginActive loginActive) {
        return ResponseEntity.ok(Response.success(memberWebUseCase.getMyInfo(loginActive.memberId())));
    }

    @Operation(summary = "내 정보 수정", description = """
        닉네임을 바꿉니다. 규칙은 가입과 같습니다 — 2~10자, 앞뒤 공백은 지우고 저장합니다. 응답은 내 정보 조회와 같은 모양입니다.
        누락 · 공백만 MEMBER_101, 길이 MEMBER_102(400). 회원 상태 오류는 내 정보 조회와 같습니다.

        **인증 필요: Authorization 헤더. 필수: 요청 바디의 nickname.**

        호출 예: `PATCH /api/v1/members/me` `{"nickname":"재채기탐정"}`""",
        security = @SecurityRequirement(name = "bearerAuth"))
    @PatchMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Response<MemberMyInfoResponse>> updateMyInfo(@AuthenticationPrincipal MemberLoginActive loginActive,
        @Valid @RequestBody MemberMyInfoUpdateRequest request) {
        return ResponseEntity.ok(Response.success(memberWebUseCase.updateMyInfo(loginActive.memberId(), request.nickname())));
    }

    @Operation(summary = "비밀번호 변경", description = """
        현재 비밀번호를 확인하고 새 비밀번호로 바꿉니다. 새 비밀번호 규칙은 가입과 같습니다(8~20자 · 영문자와 숫자 · 공백 금지). 현재와 같아도 됩니다.
        **성공하면 지금 기기는 로그인 상태로 남고, 다른 모든 기기는 로그아웃됩니다**(그 기기들의 access token 도 바로 폐기).

        카카오로만 로그인하는(비밀번호가 없는) 계정은 MEMBER_007(409) 입니다. 현재 비밀번호가 틀리면 MEMBER_005(400),
        정해진 횟수(기본 5회) 틀리면 잠기고 MEMBER_006(429, 기본 10분). 다른 기기 로그아웃에 실패하면(세션 저장소 장애) 비밀번호를 바꾸지 않고
        MEMBER_009(503) — 다시 시도하면 됩니다. 검증: 현재 비밀번호 누락 MEMBER_103 · 100자 초과 MEMBER_104, 새 비밀번호 누락 MEMBER_105 ·
        길이 MEMBER_106 · 구성 MEMBER_107.

        **인증 필요: Authorization 헤더. 필수: 요청 바디의 currentPassword, newPassword.**

        호출 예: `POST /api/v1/members/me/password` `{"currentPassword":"P@ssw0rd!","newPassword":"Sneeze2026!"}`""",
        security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping("/me/password")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Response<Void>> changePassword(@AuthenticationPrincipal MemberLoginActive loginActive,
        @Valid @RequestBody MemberPasswordChangeRequest request) {
        memberWebUseCase.changePassword(loginActive.memberId(), loginActive.sessionId(), request.currentPassword(), request.newPassword());
        return ResponseEntity.ok(Response.success());
    }

    @Operation(summary = "동의 (약관 재동의 · 건강정보 동의)", description = """
        지금 버전의 문서에 동의합니다. `TERMS_OF_SERVICE` · `PRIVACY_POLICY` 는 약관 재동의(`pendingConsents` 에 있던 항목),
        `SENSITIVE_HEALTH_INFO` 는 주간 보고를 위한 민감정보(건강정보) 별도 동의입니다. `AGE_OVER_19` 는 가입 때 한 번만 확인하므로 받지 않습니다
        (MEMBER_010, 400 — 약관을 개정해도 다시 묻지 않습니다).
        **이미 지금 버전으로 동의해 있으면 새 이력을 남기지 않고 성공합니다(멱등).** 그 밖에는 새 이력 행을 남기고 기존 이력은 고치지 않습니다.
        응답은 동의 뒤의 상태 `{pendingConsents, healthInfoAgreed, reportWritable, purgePending}` 입니다(내 정보 조회와 같은 계산).

        **지금 access token 의 scope 는 바뀌지 않습니다** — 토큰은 발급 때 계산합니다. `reportWritable` 이 true 가 됐으면
        `POST /api/v1/auth/token/reissue` 로 새 access 를 받아야 `report:write` 가 실립니다. 건강정보 동의를 철회한 뒤 보고 파기가 끝나지
        않았으면(`purgePending=true`) 다시 동의해도 파기가 끝날 때까지 `reportWritable` 은 false 입니다.

        `documentVersion` 이 서버의 현재 문서 버전과 다르면 MEMBER_011(409) — 화면의 문서 버전(legal 상수)이 낡았으니 새로 받은 뒤 다시 보냅니다.
        검증: 항목 누락 MEMBER_108, 문서 버전 누락 · 공백 MEMBER_109 · 20자 초과 MEMBER_110, 모르는 항목 값 · 깨진 JSON MEMBER_100.
        회원 상태 오류는 내 정보 조회와 같습니다.

        **인증 필요: Authorization 헤더. 필수: 요청 바디의 type, documentVersion.**

        호출 예: `POST /api/v1/members/me/consents` `{"type":"SENSITIVE_HEALTH_INFO","documentVersion":"2026-10-01"}`""",
        security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping("/me/consents")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Response<MemberConsentStatusResponse>> agreeConsent(@AuthenticationPrincipal MemberLoginActive loginActive,
        @Valid @RequestBody MemberConsentAgreeRequest request) {
        return ResponseEntity.ok(Response.success(memberWebUseCase.agreeConsent(loginActive.memberId(), request.type(), request.documentVersion())));
    }

    @Operation(summary = "동의 철회 (건강정보)", description = """
        민감정보(건강정보) 동의를 철회합니다. 철회할 수 있는 항목은 `SENSITIVE_HEALTH_INFO` 뿐이고, 다른 항목은 MEMBER_012(400) 입니다
        (이용약관 · 개인정보는 탈퇴로만 끝나고, 만 19세 확인은 철회 대상이 아닙니다).

        철회하면 지금까지의 주간 보고 원본 파기를 요청하고(철회와 같은 트랜잭션), **이 기기를 포함한 모든 기기에서 로그아웃됩니다** — 모든 refresh
        세션과 access token 을 바로 폐기하고, 응답에서 refresh 쿠키를 지웁니다(`Set-Cookie: refreshToken=; Max-Age=0`). 화면은 성공하면 로그아웃
        상태로 바꿉니다. 파기가 끝날 때까지는(`purgePending=true`) 다시 동의해도 보고할 수 없습니다.
        세션 저장소 장애로 로그아웃에 실패해도 철회는 이미 끝났으므로 200 입니다(쿠키도 지웁니다).

        **이미 철회했거나 동의한 적이 없으면 아무것도 하지 않고 200 입니다(멱등)** — 이때는 로그아웃하지 않고 쿠키도 지우지 않습니다.
        응답은 철회 뒤의 상태 `{pendingConsents, healthInfoAgreed, reportWritable, purgePending}` 입니다.

        모르는 항목 값은 MEMBER_198. 회원 상태 오류는 내 정보 조회와 같습니다.

        **인증 필요: Authorization 헤더. 필수: 경로의 type.**

        호출 예: `DELETE /api/v1/members/me/consents/SENSITIVE_HEALTH_INFO`""",
        security = @SecurityRequirement(name = "bearerAuth"))
    @DeleteMapping("/me/consents/{type}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Response<MemberConsentStatusResponse>> withdrawConsent(@AuthenticationPrincipal MemberLoginActive loginActive,
        @Parameter(description = "[필수] 철회할 동의 항목 (SENSITIVE_HEALTH_INFO 만 가능)", example = "SENSITIVE_HEALTH_INFO") @PathVariable ConsentType type) {
        MemberConsentWithdrawResult result = memberWebUseCase.withdrawConsent(loginActive.memberId(), type, loginActive.tokenId(), loginActive.expiresAt());
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok();
        if (result.loggedOut()) {
            builder.header(HttpHeaders.SET_COOKIE, MemberRefreshCookie.clear().toString());
        }
        return builder.body(Response.success(result.response()));
    }
}
