package com.sneezecast.domainlayer.region.adapter.in.web.controller;

import com.sneezecast.common.dto.Response;
import com.sneezecast.domainlayer.region.adapter.in.web.dto.request.MemberRegionUpdateRequest;
import com.sneezecast.domainlayer.region.adapter.in.web.dto.response.MemberRegionResponse;
import com.sneezecast.domainlayer.region.application.port.in.RegionWebUseCase;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 내 동네(행정동) API. 경로는 회원 하위 리소스({@code /api/v1/members/me/region})지만 {@code member_region} 을 소유하는 region 컨텍스트가 받는다
 * (modules.md). 회원은 JWT 로만 식별한다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/members/me/region")
@Tag(name = "내 동네", description = "회원이 고른 행정동 저장 · 조회 API")
public class RegionWebController {

    private final RegionWebUseCase regionWebUseCase;

    @Operation(summary = "내 동네 저장", description = """
        행정동 검색(`GET /api/v1/districts?query=`)에서 고른 코드를 내 동네로 저장합니다. 회원당 하나이고, 다시 부르면 바꿉니다(같은 코드여도 200).
        저장 전에 현행 행정동인지 확인합니다 — 없는 코드는 REGION_001(400), 폐지된 코드는 REGION_002(400)로 다시 고르게 합니다.
        행정동 확인 서비스 장애면 저장하지 않고 REGION_004(503) 입니다. 같은 회원의 첫 저장이 동시에 겹치면 REGION_003(409) — 다시 보내면 됩니다.

        **인증 필요: Authorization 헤더. 필수: 요청 바디의 code(숫자 8자리).** 없으면 REGION_101, 형식이 틀리면 REGION_102(400).
        GPS · 주소는 받지 않습니다.

        응답은 저장된 동네입니다(`abolished` 는 항상 false).

        호출 예: `PUT /api/v1/members/me/region` `{"code":"11230510"}`""",
        security = @SecurityRequirement(name = "bearerAuth"))
    @PutMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Response<MemberRegionResponse>> saveMyRegion(
        @AuthenticationPrincipal MemberLoginActive loginActive, @Valid @RequestBody MemberRegionUpdateRequest request) {
        return ResponseEntity.ok(Response.success(regionWebUseCase.saveMyRegion(loginActive.memberId(), request.code())));
    }

    @Operation(summary = "내 동네 조회", description = """
        저장한 내 동네를 돌려줍니다. 아직 고르지 않았으면 200 에 `dataBody: null` 입니다 — 동네 선택 화면으로 이끕니다.
        이름 · 폐지 여부는 그때 행정동 마스터에서 다시 읽습니다. 고른 뒤 행정동이 폐지됐으면 `abolished: true` 이고 저장 값은 자동으로 바뀌지 않으니
        다시 고르게 합니다. 행정동 확인 서비스 장애면 REGION_004(503) 입니다.

        **인증 필요: Authorization 헤더.** 파라미터는 없습니다.

        호출 예: `GET /api/v1/members/me/region`""",
        security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Response<MemberRegionResponse>> getMyRegion(@AuthenticationPrincipal MemberLoginActive loginActive) {
        return ResponseEntity.ok(Response.success(regionWebUseCase.getMyRegion(loginActive.memberId())));
    }
}
