package com.sneezecast.domainlayer.report.adapter.in.web.controller;

import com.sneezecast.common.dto.Response;
import com.sneezecast.domainlayer.report.adapter.in.web.dto.request.WeeklyReportRequest;
import com.sneezecast.domainlayer.report.adapter.in.web.dto.response.WeeklyReportResponse;
import com.sneezecast.domainlayer.report.application.command.ReportSubmitCommand;
import com.sneezecast.domainlayer.report.application.port.in.ReportWebUseCase;
import com.sneezecast.security.common.constant.SecurityScope;
import com.sneezecast.security.common.dto.MemberLoginActive;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 본인 이번 주 보고 API. 건강 · 증상은 민감정보라 <b>조회도 쓰기와 같은 권한</b>({@code report:write} — 민감정보 동의를 마친 회원)으로 막는다.
 * URL 수준은 security-core 가 전부 열어 두므로 보호는 {@code @PreAuthorize} 에만 달려 있다.
 *
 * <p>리소스는 "이번 주" 하나다 — 경로 · 본문에 주를 받지 않고 서버가 KST 현재 시각으로 정한다. 지난 주 보고는 쓸 수도 읽을 수도 없다.
 * 회원은 토큰의 {@code sub} 로만 알고, 응답에 회원 ID · 보고자 키 · 보고 ID 를 싣지 않는다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/reports/current")
@Tag(name = "주간 보고", description = "본인 이번 주 건강 보고 제출 · 조회 · 취소 API")
public class ReportWebController {

    private final ReportWebUseCase reportWebUseCase;

    @Operation(summary = "이번 주 보고 제출 · 수정", description = """
        이번 주(ISO 주, 월요일 시작, KST) 보고를 저장합니다. 이번 주에 처음이면 새로 저장하고, 이미 있으면 행정동 · 증상군을 이번 값으로 바꿉니다
        (같은 주는 한 번만 셉니다). 증상이 없으면 `symptomGroups: []` 로 보냅니다 — 증상 없음도 정상 보고입니다.

        **인증 필요: Authorization 헤더, 건강정보 동의(scope `report:write`).** 토큰이 없으면 SECURITY_001(401), 동의 전이면 SECURITY_006(403).
        **필수: districtCode(숫자 8자리) · symptomGroups(배열, null 불가).** 비었으면 REPORT_101, 형식이 틀리면 REPORT_102, symptomGroups 가 없으면
        REPORT_103, 원소가 null 이면 REPORT_104, 같은 값이 두 번이면 REPORT_105, 알 수 없는 값이면 REPORT_100(400) 입니다.
        없는 행정동은 REPORT_002, 폐지된 행정동은 REPORT_003(400) — 동네를 다시 고르게 합니다.
        같은 주 보고가 동시에 처리돼 서버가 다시 시도해도 지면 REPORT_001(409) — 잠시 뒤 같은 요청을 다시 보내면 됩니다.

        호출 예: `PUT /api/v1/reports/current` `{"districtCode":"11230510","symptomGroups":["RESPIRATORY"]}`""",
        security = @SecurityRequirement(name = "bearerAuth"))
    @PutMapping
    @PreAuthorize("hasAuthority('" + SecurityScope.REPORT_WRITE_AUTHORITY + "')")
    public ResponseEntity<Response<WeeklyReportResponse>> submitCurrentReport(
        @AuthenticationPrincipal MemberLoginActive loginActive, @Valid @RequestBody WeeklyReportRequest request) {
        return ResponseEntity.ok(Response.success(reportWebUseCase.submitCurrent(loginActive.memberId(), toCommand(request))));
    }

    @Operation(summary = "이번 주 보고 조회", description = """
        본인의 이번 주 보고를 돌려줍니다. 아직 보고하지 않았으면 200 이고 `dataBody` 가 null 입니다.

        **인증 필요: Authorization 헤더, 건강정보 동의(scope `report:write`).** 파라미터는 없습니다.

        호출 예: `GET /api/v1/reports/current`""",
        security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping
    @PreAuthorize("hasAuthority('" + SecurityScope.REPORT_WRITE_AUTHORITY + "')")
    public ResponseEntity<Response<WeeklyReportResponse>> getCurrentReport(@AuthenticationPrincipal MemberLoginActive loginActive) {
        return ResponseEntity.ok(Response.success(reportWebUseCase.getCurrent(loginActive.memberId())));
    }

    @Operation(summary = "이번 주 보고 취소", description = """
        본인의 이번 주 보고를 지웁니다. 이번 주 집계에서 빠집니다. 보고가 없어도 200 입니다(멱등). 취소한 뒤 다시 제출하면 새 보고로 저장됩니다.

        **인증 필요: Authorization 헤더, 건강정보 동의(scope `report:write`).** 파라미터는 없습니다.

        호출 예: `DELETE /api/v1/reports/current`""",
        security = @SecurityRequirement(name = "bearerAuth"))
    @DeleteMapping
    @PreAuthorize("hasAuthority('" + SecurityScope.REPORT_WRITE_AUTHORITY + "')")
    public ResponseEntity<Response<Void>> cancelCurrentReport(@AuthenticationPrincipal MemberLoginActive loginActive) {
        reportWebUseCase.cancelCurrent(loginActive.memberId());
        return ResponseEntity.ok(Response.success());
    }

    /** web DTO → application 명령 변환은 adapter 가 맡는다 — application 이 adapter 타입을 모르게 한다 (architecture-guide §3). */
    private static ReportSubmitCommand toCommand(WeeklyReportRequest request) {
        return ReportSubmitCommand.of(request.districtCode(), request.symptomGroups());
    }
}
