package com.sneezecast.domainlayer.member.adapter.in.web.controller;

import com.sneezecast.common.dto.Response;
import com.sneezecast.domainlayer.member.adapter.in.web.dto.request.MemberNotificationSettingUpdateRequest;
import com.sneezecast.domainlayer.member.adapter.in.web.dto.response.MemberNotificationSettingResponse;
import com.sneezecast.domainlayer.member.application.port.in.MemberNotificationSettingWebUseCase;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 알림 수신 설정 API. 알림을 받을지 고른 값(설정)만 다룬다 — 실제로 보낼 곳(PWA 푸시 구독)과 발송은 2단계다. member 컨텍스트가 받아 검증 오류는
 * MEMBER 대역이고, 회원은 JWT 로만 식별한다. 건강정보가 아니라 {@code report:write} 를 요구하지 않는다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/members/me/notification-settings")
@Tag(name = "알림 설정", description = "주간 보고 요청 · 동네 안내 알림 수신 설정 조회 · 변경 API")
public class MemberNotificationSettingWebController {

    private final MemberNotificationSettingWebUseCase memberNotificationSettingWebUseCase;

    @Operation(summary = "알림 설정 조회", description = """
        알림 수신 설정 `{weeklyReport, regionNotice}` 를 돌려줍니다. `weeklyReport` 는 주간 보고 요청(월요일 아침), `regionNotice` 는 운영자가 검토 ·
        발행한 동네 안내입니다. **한 번도 바꾸지 않았으면 모두 false** 입니다. 설정을 켜도 푸시 구독(2단계)이 없으면 알림은 가지 않습니다.

        회원 행이 없으면 MEMBER_004(404), 탈퇴 MEMBER_002 · 정지 MEMBER_003(403).

        **인증 필요: Authorization 헤더.** 파라미터는 없습니다. 증상 보고 동의(report:write)는 필요 없습니다.

        호출 예: `GET /api/v1/members/me/notification-settings`""",
        security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Response<MemberNotificationSettingResponse>> getMyNotificationSettings(@AuthenticationPrincipal MemberLoginActive loginActive) {
        return ResponseEntity.ok(Response.success(memberNotificationSettingWebUseCase.getMyNotificationSettings(loginActive.memberId())));
    }

    @Operation(summary = "알림 설정 변경", description = """
        보낸 항목만 바꾸고 **바뀐 뒤의 설정 전부**를 돌려줍니다. 보내지 않은 항목은 DB 에서 그대로 둡니다 — 다른 탭 · 기기의 요청이 동시에 다른 항목을
        바꿔도 둘 다 남습니다. 첫 변경(설정 행을 처음 만들 때)이 동시에 겹친 경우만 서버가 한 번 다시 하고, 그래도 겹치면 MEMBER_013(409) — 다시
        보내면 됩니다.

        검증(400): 항목이 하나도 없는 본문 `{}` MEMBER_111, `weeklyReport` 가 true / false 가 아님 MEMBER_112, `regionNotice` 가 true / false 가 아님
        MEMBER_113(문자열 "true" · 숫자 · null 도 거절합니다), 모르는 항목 MEMBER_114, 깨진 JSON · 객체가 아닌 본문 · 본문 없음 MEMBER_100.
        회원 상태 오류는 조회와 같습니다.

        **인증 필요: Authorization 헤더. 필수: 요청 바디에 weeklyReport · regionNotice 중 하나 이상.**

        호출 예: `PATCH /api/v1/members/me/notification-settings` `{"weeklyReport":true}`""",
        security = @SecurityRequirement(name = "bearerAuth"))
    @PatchMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Response<MemberNotificationSettingResponse>> changeMyNotificationSettings(
        @AuthenticationPrincipal MemberLoginActive loginActive, @Valid @RequestBody MemberNotificationSettingUpdateRequest request) {
        return ResponseEntity.ok(Response.success(memberNotificationSettingWebUseCase.changeMyNotificationSettings(loginActive.memberId(), request.toCommand())));
    }
}
