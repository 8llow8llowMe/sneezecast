package com.sneezecast.domainlayer.region.adapter.in.web.controller;

import com.sneezecast.common.dto.Response;
import com.sneezecast.domainlayer.region.adapter.in.web.dto.request.MemberInterestRegionAddRequest;
import com.sneezecast.domainlayer.region.adapter.in.web.dto.response.MemberRegionResponse;
import com.sneezecast.domainlayer.region.application.exception.RegionValidationMessage;
import com.sneezecast.domainlayer.region.application.port.in.InterestRegionWebUseCase;
import com.sneezecast.persistence.dto.SliceResponse;
import com.sneezecast.security.common.dto.MemberLoginActive;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관심 동네(내 동네 말고 지켜볼 행정동) API. 내 동네({@code RegionWebController})와 경로가 달라 따로 두지만 같은 region 컨텍스트가 받고,
 * 인증 · 오류 · 응답 모양({@code MemberRegionResponse})이 같다. 회원은 JWT 로만 식별한다. 건강정보가 아니라 {@code report:write} 를 요구하지 않는다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/members/me/interest-regions")
@Tag(name = "관심 동네", description = "내 동네 말고 지켜볼 행정동 조회 · 추가 · 삭제 API")
public class InterestRegionWebController {

    private final InterestRegionWebUseCase interestRegionWebUseCase;

    @Operation(summary = "관심 동네 목록", description = """
        고른 관심 동네를 고른 순서로 돌려줍니다. 하나도 없으면 빈 목록입니다. 상한만큼만 있어 한 번에 모두 주고 `hasNext` 는 항상 false 입니다.
        이름 · 폐지 여부는 그때 행정동 마스터에서 다시 읽습니다 — 고른 뒤 폐지됐으면 `abolished: true`(행정동 마스터에 코드가 없으면 name · sigungu 가
        null)이고 자동으로 지우지 않습니다. 행정동 확인 서비스 장애면 REGION_004(503) 입니다.

        **인증 필요: Authorization 헤더.** 파라미터는 없습니다.

        호출 예: `GET /api/v1/members/me/interest-regions`""",
        security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Response<SliceResponse<MemberRegionResponse>>> getMyInterestRegions(@AuthenticationPrincipal MemberLoginActive loginActive) {
        return ResponseEntity.ok(Response.success(interestRegionWebUseCase.getMyInterestRegions(loginActive.memberId())));
    }

    @Operation(summary = "관심 동네 추가", description = """
        행정동 검색(`GET /api/v1/districts?query=`)에서 고른 코드를 관심 동네로 더하고, 더한 뒤의 목록을 고른 순서로 돌려줍니다(새 동네가 끝).
        현행 행정동만 더할 수 있습니다 — 없는 코드는 REGION_001(400), 폐지된 코드는 REGION_002(400), 행정동 확인 서비스 장애면 더하지 않고 REGION_004(503).
        내 동네와 같으면 REGION_007, 이미 고른 동네면 REGION_006, 상한(기본 3곳)이 찼으면 REGION_005, 같은 회원의 추가가 동시에 겹치면 REGION_003
        (모두 409 — 다시 보내면 풀립니다). **409 오류 봉투에는 목록이 없습니다** — 목록 조회를 다시 불러 화면을 맞춥니다.

        **인증 필요: Authorization 헤더. 필수: 요청 바디의 code(숫자 8자리).** 없으면 REGION_101, 형식이 틀리면 REGION_102(400).
        GPS · 주소는 받지 않습니다.

        더한 뒤 목록 이름을 읽다 행정동 확인 서비스가 실패하면 503 이지만 추가는 남아 있습니다(다시 보내면 REGION_006).

        호출 예: `POST /api/v1/members/me/interest-regions` `{"code":"11230510"}`""",
        security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Response<SliceResponse<MemberRegionResponse>>> addMyInterestRegion(
        @AuthenticationPrincipal MemberLoginActive loginActive, @Valid @RequestBody MemberInterestRegionAddRequest request) {
        return ResponseEntity.ok(Response.success(interestRegionWebUseCase.addMyInterestRegion(loginActive.memberId(), request.code())));
    }

    @Operation(summary = "관심 동네 삭제", description = """
        관심 동네 하나를 빼고, 뺀 뒤의 목록을 고른 순서로 돌려줍니다. 목록에 없는 코드여도 성공입니다(이미 지운 동네를 다시 지움).
        폐지된 동네도 같은 코드로 지웁니다. 남은 목록 이름을 읽다 행정동 확인 서비스가 실패하면 REGION_004(503) 이지만 삭제는 반영돼 있습니다.

        **인증 필요: Authorization 헤더. 필수: code(경로, 숫자 8자리).** 형식이 틀리면 REGION_102(400).

        호출 예: `DELETE /api/v1/members/me/interest-regions/11230510`""",
        security = @SecurityRequirement(name = "bearerAuth"))
    @DeleteMapping("/{code}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Response<SliceResponse<MemberRegionResponse>>> removeMyInterestRegion(
        @AuthenticationPrincipal MemberLoginActive loginActive,
        @Parameter(description = "지울 관심 동네의 SGIS 행정동 코드 (숫자 8자리)", example = "11230510")
        @PathVariable @Pattern(regexp = RegionValidationMessage.DISTRICT_CODE_REGEXP, message = RegionValidationMessage.DISTRICT_CODE_FORMAT_INVALID) String code) {
        return ResponseEntity.ok(Response.success(interestRegionWebUseCase.removeMyInterestRegion(loginActive.memberId(), code)));
    }
}
