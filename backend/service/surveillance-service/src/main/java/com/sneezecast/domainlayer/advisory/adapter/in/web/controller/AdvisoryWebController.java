package com.sneezecast.domainlayer.advisory.adapter.in.web.controller;

import com.sneezecast.common.dto.Response;
import com.sneezecast.domainlayer.advisory.adapter.in.web.dto.request.RegionAdvisoryRequest;
import com.sneezecast.domainlayer.advisory.adapter.in.web.dto.response.RegionAdvisoryResponse;
import com.sneezecast.domainlayer.advisory.application.port.in.AdvisoryWebUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 동네 예방 안내 공개 API (#215). 게이트웨이 라우트 {@code /api/v1/advisories/**} 안에 둔다. 비로그인 둘러보기에서도 보므로 {@code @PreAuthorize} 를
 * 달지 않는다 (URL 수준은 security-core 가 전부 열어 둔다). 운영자가 발행한 안내만 보이고 운영자 · 이력 정보는 싣지 않는다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/advisories")
@Tag(name = "동네 예방 안내", description = "운영자가 검토 · 발행한 동네 예방 안내 공개 조회 API")
public class AdvisoryWebController {

    private final AdvisoryWebUseCase advisoryWebUseCase;

    @Operation(summary = "동네 안내", description = """
        행정동 하나 · 주 하나의 예방 안내를 돌려줍니다. **운영자가 발행한 안내만** 보입니다(초안 · 수정 · 승인 단계는 보이지 않습니다). 응답은 셋 중 하나입니다.
        - **발행 중**: `advisory` = 제목 · 본문 · 발행 시각 · 인용 수치(`citation` — 운영자가 검토할 때 고정한 값, 집계가 다시 계산돼도 바뀌지 않음) ·
          정정 이력(`corrections` — 정정 시각, 최근 것부터), `retraction` = null. 같은 주에 철회 뒤 새 안내가 발행됐으면 새 안내만 보입니다.
        - **철회됨**: `advisory` = null, `retraction` = 철회 시각. 내린 안내의 제목 · 본문 · 인용 수치는 싣지 않습니다. 정정으로 내려간 안내는 철회 표시가
          아니라 정정한 안내의 `corrections` 로 보입니다.
        - **안내 없음**: `dataBody` 가 null 입니다.

        정정 · 철회 사유(운영자 메모)와 운영자 · 버전 · 안내 ID 는 싣지 않습니다. 시각은 ISO-8601 UTC(초 단위)입니다.

        인증 불필요. **필수: districtCode(SGIS 행정동 코드 숫자 8자리)** — 없으면 ADVISORY_101, 형식이 틀리면 ADVISORY_102, 없는 코드는 ADVISORY_008(404).
        폐지된 코드는 오류가 아닙니다. **필수: week(YYYY-Www)** — 없으면 ADVISORY_103, 형식이 틀리면 ADVISORY_104, 미래 주 · 51주보다 이전 · 그 해에 없는 주는
        ADVISORY_009(400) 입니다.

        호출 예: `GET /api/v1/advisories?districtCode=11240660&week=2026-W40`""")
    @GetMapping
    public ResponseEntity<Response<RegionAdvisoryResponse>> getRegionAdvisory(@ParameterObject @Valid @ModelAttribute RegionAdvisoryRequest request) {
        return ResponseEntity.ok(Response.success(advisoryWebUseCase.getRegionAdvisory(request.districtCode(), request.week())));
    }
}
