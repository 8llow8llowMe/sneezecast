package com.sneezecast.domainlayer.member.adapter.in.web.controller;

import com.sneezecast.common.dto.Response;
import com.sneezecast.domainlayer.member.adapter.in.web.dto.request.MemberMyInfoUpdateRequest;
import com.sneezecast.domainlayer.member.adapter.in.web.dto.request.MemberPasswordChangeRequest;
import com.sneezecast.domainlayer.member.adapter.in.web.dto.request.MemberPasswordSetupRequest;
import com.sneezecast.domainlayer.member.adapter.in.web.dto.response.MemberMyInfoResponse;
import com.sneezecast.domainlayer.member.application.port.in.MemberWebUseCase;
import com.sneezecast.security.common.dto.MemberLoginActive;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/members")
@Tag(name = "회원", description = "내 정보 조회 · 수정, 비밀번호 변경 · 설정 API")
public class MemberWebController {

    private final MemberWebUseCase memberWebUseCase;

    @Operation(summary = "내 정보 조회", description = """
        로그인한 회원의 내 정보를 돌려줍니다 — 이메일, 닉네임, 가입 방법(`provider`: EMAIL · KAKAO), 비밀번호가 있는지(`hasPassword`), 역할.
        `hasPassword` 가 false 면(소셜 가입) 비밀번호 설정을, true 면 비밀번호 변경을 보여 줍니다.
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

        비밀번호가 없는 소셜 계정은 MEMBER_007(409) — 비밀번호 설정을 이용합니다. 현재 비밀번호가 틀리면 MEMBER_005(400),
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

    @Operation(summary = "비밀번호 설정", description = """
        비밀번호가 없는 소셜 가입 계정에 비밀번호를 정합니다. 설정하면 이메일 + 비밀번호로도 로그인할 수 있습니다. 규칙은 가입과 같습니다.
        세션 처리는 비밀번호 변경과 같습니다 — 지금 기기는 남고 다른 모든 기기는 로그아웃됩니다.

        이미 비밀번호가 있으면 MEMBER_008(409) — 비밀번호 변경을 이용합니다. 다른 기기 로그아웃 실패는 MEMBER_009(503).
        검증: 새 비밀번호 누락 MEMBER_105 · 길이 MEMBER_106 · 구성 MEMBER_107.

        **인증 필요: Authorization 헤더. 필수: 요청 바디의 newPassword.**

        호출 예: `POST /api/v1/members/me/password/setup` `{"newPassword":"Sneeze2026!"}`""",
        security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping("/me/password/setup")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Response<Void>> setupPassword(@AuthenticationPrincipal MemberLoginActive loginActive,
        @Valid @RequestBody MemberPasswordSetupRequest request) {
        memberWebUseCase.setupPassword(loginActive.memberId(), loginActive.sessionId(), request.newPassword());
        return ResponseEntity.ok(Response.success());
    }
}
