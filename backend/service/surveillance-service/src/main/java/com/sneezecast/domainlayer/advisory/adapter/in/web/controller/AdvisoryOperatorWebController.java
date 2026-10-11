package com.sneezecast.domainlayer.advisory.adapter.in.web.controller;

import com.sneezecast.common.dto.Response;
import com.sneezecast.domainlayer.advisory.adapter.in.web.dto.request.AdvisoryDraftCreateRequest;
import com.sneezecast.domainlayer.advisory.adapter.in.web.dto.request.AdvisoryEditRequest;
import com.sneezecast.domainlayer.advisory.adapter.in.web.dto.request.AdvisoryRetractRequest;
import com.sneezecast.domainlayer.advisory.adapter.in.web.dto.request.AdvisoryReviewCandidateRequest;
import com.sneezecast.domainlayer.advisory.adapter.in.web.dto.request.AdvisoryVersionRequest;
import com.sneezecast.domainlayer.advisory.adapter.in.web.dto.response.AdvisoryOperatorDetailResponse;
import com.sneezecast.domainlayer.advisory.adapter.in.web.dto.response.AdvisoryOperatorResponse;
import com.sneezecast.domainlayer.advisory.adapter.in.web.dto.response.AdvisoryReviewCandidateListResponse;
import com.sneezecast.domainlayer.advisory.application.command.AdvisoryDraftCommand;
import com.sneezecast.domainlayer.advisory.application.command.AdvisoryEditCommand;
import com.sneezecast.domainlayer.advisory.application.command.AdvisoryRetractCommand;
import com.sneezecast.domainlayer.advisory.application.exception.AdvisoryErrorCode;
import com.sneezecast.domainlayer.advisory.application.exception.AdvisoryException;
import com.sneezecast.domainlayer.advisory.application.port.in.AdvisoryOperatorWebUseCase;
import com.sneezecast.domainlayer.report.domain.model.ReportWeek;
import com.sneezecast.security.common.dto.MemberLoginActive;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 운영자 예방 안내 API (#246). 게이트웨이 라우트 {@code /api/v1/advisories/**} 안의 {@code /admin} 아래에 둔다(라우트 추가 없음). URL 수준은
 * security-core 가 전부 열어 두므로 보호는 <b>클래스에 건 {@code @PreAuthorize}</b> 에 달려 있다 — 운영 API 는 OPERATOR · ADMIN 이 연다
 * ({@code SecurityRole}). 검토 후보(#266) · 정정 · 발행 이력(#267)도 이 컨트롤러에 메서드를 더해 같은 보호를 받는다. 리터럴 경로
 * {@code /candidates} 는 {@code /{advisoryId}} 보다 먼저 맞는다(변수가 적은 패턴이 우선).
 *
 * <p>운영자는 토큰 {@code sub} 로만 알고(클라이언트 헤더 · 본문으로 받지 않는다) 이력 {@code operator_id} 에 남는다. 전이 요청은 화면이 읽은 {@code version}
 * 을 싣는다. 자동 발행 · AI 초안 경로는 없다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/advisories/admin")
@PreAuthorize(AdvisoryOperatorWebController.OPERATOR_AUTHORITY)
@Tag(name = "운영자 예방 안내", description = "운영자의 예방 안내 검토 후보 · 초안 생성 · 수정 · 승인 · 발행 · 철회 · 상세 API (OPERATOR · ADMIN)")
public class AdvisoryOperatorWebController {

    /** 운영 API 권한 — security-core {@code SecurityRole}: ADMIN 은 운영 업무도 할 수 있다. authority 는 역할 이름 그대로(ROLE_ 접두어 없음). */
    static final String OPERATOR_AUTHORITY = "hasAnyAuthority('OPERATOR', 'ADMIN')";

    private static final String AUTH_NOTE = "**인증 필요: 운영자(OPERATOR · ADMIN).** 토큰이 없으면 SECURITY_001(401), 운영자가 아니면 SECURITY_006(403).";

    private final AdvisoryOperatorWebUseCase advisoryOperatorWebUseCase;

    @Operation(summary = "초안 생성", description = """
        동네 · 주의 **마감된 · 자료 부족이 아닌** 집계로 안내 초안(DRAFT)을 만듭니다. 인용 수치는 서버가 집계에서 복사해 고정합니다(요청에 싣지 않습니다).
        같은 동네 · 주에 초안이 여럿이어도 됩니다.

        """ + AUTH_NOTE + """

        **필수: districtCode(숫자 8자리) · isoWeek(YYYY-Www) · title(100자 이하) · body(16,383자 이하).** 없음 ADVISORY_101 · 103 · 105 · 107, 형식 102 · 104,
        제목 · 본문 길이 106 · 108, 표현할 수 없는 주(53주가 없는 해의 W53, 연도 1000 미만) ADVISORY_010(400). 집계 행 없음 ADVISORY_002(404), 마감 전 ADVISORY_003 ·
        자료 부족 ADVISORY_004(409).

        호출 예: `POST /api/v1/advisories/admin` `{"districtCode":"11240660","isoWeek":"2026-W40","title":"…","body":"…"}`""",
        security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping
    public ResponseEntity<Response<AdvisoryOperatorResponse>> createDraft(@AuthenticationPrincipal MemberLoginActive loginActive,
        @Valid @RequestBody AdvisoryDraftCreateRequest request) {
        return ResponseEntity.ok(Response.success(advisoryOperatorWebUseCase.createDraft(loginActive.memberId(), toCommand(request))));
    }

    @Operation(summary = "검토 후보", description = """
        한 주의 예방 안내 검토 후보를 돌려줍니다. 신호는 조회 때 그 주 · 전주 집계로 계산합니다(저장하지 않습니다).
        - **기준선 대비 변화(BASELINE)**: 집계 단계가 조금 늘었어요(SLIGHT) · 많이 늘었어요(HIGH).
        - **참여 급증(SURGE)**: 이번 주 · 전주가 모두 참여 부족이 아니고 참여자가 전주보다 `surgeChangePercent`(30)% 이상 늘었다.
        - **반복 보고 의심(REPEAT)**: 참여 부족이 아니고 수정된 보고가 참여자의 `repeatRevisedPercent`(20)% 이상이다.

        신호가 하나라도 있거나, 신호가 없어도 그 주에 안내가 있는 동네가 후보입니다. **자료 부족 동네도 급증 · 반복 신호가 있으면 후보**이고 참여 · 전주 참여 ·
        수정 수만 싣습니다 — 증상 수 · 비율 · 기준선 · 증상군 키가 없고 `draftable` 이 false 입니다. 참여 부족(LOW_SAMPLE) 칸은 신호가 나지 않습니다.
        `advisory` 는 그 동네 · 주의 안내 하나(발행 중 → 검토 중(가장 최근) → 가장 최근 철회)의 ID · 상태 · 버전이고 없으면 null 입니다.

        """ + AUTH_NOTE + """

        **선택: week(YYYY-Www)** — 생략하면 현재 주 − 1(마감될 수 있는 가장 최근 주). 형식이 틀리면 ADVISORY_104, 미래 주 · 51주보다 이전 · 그 해에 없는 주는
        ADVISORY_009(400).

        호출 예: `GET /api/v1/advisories/admin/candidates?week=2026-W40`""",
        security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping("/candidates")
    public ResponseEntity<Response<AdvisoryReviewCandidateListResponse>> getCandidates(
        @ParameterObject @Valid @ModelAttribute AdvisoryReviewCandidateRequest request) {
        return ResponseEntity.ok(Response.success(advisoryOperatorWebUseCase.getCandidates(request.week())));
    }

    @Operation(summary = "안내 상세", description = """
        안내 한 건과 그 전 이력(생성 · 전이 · 수정, 오래된 것부터)을 돌려줍니다. 이력에는 처리한 운영자 ID 와 수정 · 철회 메모가 있습니다(사용자 화면에는 보이지 않습니다).

        """ + AUTH_NOTE + """
        없는 안내는 ADVISORY_001(404), 숫자가 아닌 ID 는 ADVISORY_198(400).

        호출 예: `GET /api/v1/advisories/admin/7250000000000000000`""",
        security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping("/{advisoryId}")
    public ResponseEntity<Response<AdvisoryOperatorDetailResponse>> getDetail(
        @Parameter(description = "안내 ID", example = "7250000000000000000") @PathVariable long advisoryId) {
        return ResponseEntity.ok(Response.success(advisoryOperatorWebUseCase.getDetail(advisoryId)));
    }

    @Operation(summary = "수정", description = """
        제목 · 본문을 고칩니다 → EDITED. 초안 · AI 초안 · 수정됨 · 승인됨에서만 됩니다 — **승인된 안내를 고치면 다시 승인받아야** 발행할 수 있습니다.
        발행 · 철회된 안내는 고칠 수 없습니다(ADVISORY_005 — 발행된 안내는 정정으로 바로잡습니다).

        """ + AUTH_NOTE + """

        **필수: title · body · version.** 제목 105 · 106, 본문 107 · 108, 메모(선택) 500자 초과 109, version 없음 112 · 음수 113(400).
        없는 안내 ADVISORY_001(404), 버전이 다르면 ADVISORY_006 · 할 수 없는 상태면 ADVISORY_005(409) — 화면은 상세를 다시 읽습니다.

        호출 예: `PUT /api/v1/advisories/admin/7250000000000000000` `{"title":"…","body":"…","memo":"표현 다듬기","version":0}`""",
        security = @SecurityRequirement(name = "bearerAuth"))
    @PutMapping("/{advisoryId}")
    public ResponseEntity<Response<AdvisoryOperatorResponse>> edit(@AuthenticationPrincipal MemberLoginActive loginActive,
        @Parameter(description = "안내 ID", example = "7250000000000000000") @PathVariable long advisoryId, @Valid @RequestBody AdvisoryEditRequest request) {
        AdvisoryEditCommand command = new AdvisoryEditCommand(request.version(), request.title(), request.body(), request.memo());
        return ResponseEntity.ok(Response.success(advisoryOperatorWebUseCase.edit(loginActive.memberId(), advisoryId, command)));
    }

    @Operation(summary = "승인", description = """
        현재 본문을 승인합니다 → APPROVED. 초안 · AI 초안 · 수정됨에서만 됩니다. 승인은 발행이 아닙니다 — 발행은 따로 부릅니다.

        """ + AUTH_NOTE + """

        **필수: version.** 없음 112 · 음수 113(400). 없는 안내 ADVISORY_001(404), 버전이 다르면 ADVISORY_006 · 할 수 없는 상태면 ADVISORY_005(409).

        호출 예: `POST /api/v1/advisories/admin/7250000000000000000/approve` `{"version":1}`""",
        security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping("/{advisoryId}/approve")
    public ResponseEntity<Response<AdvisoryOperatorResponse>> approve(@AuthenticationPrincipal MemberLoginActive loginActive,
        @Parameter(description = "안내 ID", example = "7250000000000000000") @PathVariable long advisoryId, @Valid @RequestBody AdvisoryVersionRequest request) {
        return ResponseEntity.ok(Response.success(advisoryOperatorWebUseCase.approve(loginActive.memberId(), advisoryId, request.version())));
    }

    @Operation(summary = "발행", description = """
        승인된 안내를 발행합니다 → PUBLISHED. 이때부터 동네 안내(`GET /api/v1/advisories`)와 홈 · 지도의 발행 안내 요약에 보입니다. 승인됨에서만 됩니다.
        **같은 동네 · 주에 발행 중인 안내는 하나뿐**입니다 — 이미 있으면 ADVISORY_007(409)이고, 바꾸려면 그 안내를 철회 · 정정합니다.

        """ + AUTH_NOTE + """

        **필수: version.** 없음 112 · 음수 113(400). 없는 안내 ADVISORY_001(404), 버전이 다르면 ADVISORY_006 · 할 수 없는 상태면 ADVISORY_005 ·
        발행 중인 안내가 있으면 ADVISORY_007(409).

        호출 예: `POST /api/v1/advisories/admin/7250000000000000000/publish` `{"version":2}`""",
        security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping("/{advisoryId}/publish")
    public ResponseEntity<Response<AdvisoryOperatorResponse>> publish(@AuthenticationPrincipal MemberLoginActive loginActive,
        @Parameter(description = "안내 ID", example = "7250000000000000000") @PathVariable long advisoryId, @Valid @RequestBody AdvisoryVersionRequest request) {
        return ResponseEntity.ok(Response.success(advisoryOperatorWebUseCase.publish(loginActive.memberId(), advisoryId, request.version())));
    }

    @Operation(summary = "철회", description = """
        발행 중인 안내를 내립니다 → RETRACTED(끝 상태). 발행됨에서만 됩니다. 동네 안내에는 "철회된 안내" 로 철회 시각만 남고 제목 · 본문 · 수치는 보이지 않습니다.
        **사유는 필수이고 이력 메모로 남지만 사용자 화면에는 보이지 않습니다.**

        """ + AUTH_NOTE + """

        **필수: reason(500자 이하) · version.** 사유 없음 · 공백 110, 500자 초과 111, version 없음 112 · 음수 113(400). 없는 안내 ADVISORY_001(404),
        버전이 다르면 ADVISORY_006 · 할 수 없는 상태면 ADVISORY_005(409).

        호출 예: `POST /api/v1/advisories/admin/7250000000000000000/retract` `{"reason":"집계 오류 확인","version":3}`""",
        security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping("/{advisoryId}/retract")
    public ResponseEntity<Response<AdvisoryOperatorResponse>> retract(@AuthenticationPrincipal MemberLoginActive loginActive,
        @Parameter(description = "안내 ID", example = "7250000000000000000") @PathVariable long advisoryId, @Valid @RequestBody AdvisoryRetractRequest request) {
        AdvisoryRetractCommand command = new AdvisoryRetractCommand(request.version(), request.reason());
        return ResponseEntity.ok(Response.success(advisoryOperatorWebUseCase.retract(loginActive.memberId(), advisoryId, command)));
    }

    /**
     * web DTO → application 명령 변환은 adapter 가 맡는다 (architecture-guide §3). 형식(YYYY-Www, W01~W53)은 검증이 봤지만 53주가 없는 해의 W53 · 연도 1000 미만은 형식으로 거를 수
     * 없어 {@link ReportWeek#parse} 가 {@code IllegalArgumentException} 을 던진다 — 500 으로 새지 않게 {@code ADVISORY_010}(400)으로 바꾼다.
     */
    private static AdvisoryDraftCommand toCommand(AdvisoryDraftCreateRequest request) {
        ReportWeek isoWeek;
        try {
            isoWeek = ReportWeek.parse(request.isoWeek());
        } catch (IllegalArgumentException exception) {
            throw new AdvisoryException(AdvisoryErrorCode.WEEK_NOT_FOUND, request.isoWeek());
        }
        return new AdvisoryDraftCommand(request.districtCode(), isoWeek, request.title(), request.body());
    }
}
