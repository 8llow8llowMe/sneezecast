package com.sneezecast.domainlayer.aggregate.adapter.in.web.controller;

import com.sneezecast.common.dto.Response;
import com.sneezecast.domainlayer.aggregate.adapter.in.web.dto.request.DistrictWeeklyAggregateListRequest;
import com.sneezecast.domainlayer.aggregate.adapter.in.web.dto.response.DistrictWeeklyAggregateListResponse;
import com.sneezecast.domainlayer.aggregate.adapter.in.web.dto.response.DistrictWeeklyAggregateResponse;
import com.sneezecast.domainlayer.aggregate.application.exception.AggregateValidationMessage;
import com.sneezecast.domainlayer.aggregate.application.port.in.AggregateWebUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 행정동 주간 집계 공개 API (홈 · 지도). 게이트웨이 라우트 {@code /api/v1/districts/**} 안에 둔다. 비로그인 둘러보기에서도 보므로 {@code @PreAuthorize}
 * 를 달지 않는다 (URL 수준은 security-core 가 전부 열어 둔다). 보고자 · 회원 정보를 싣지 않는다.
 *
 * <p>{@code /weekly-aggregates} 는 행정동 단건({@code /{code}}, district 컨텍스트)과 경로가 겹치지만 Spring MVC 는 글자 그대로인 경로를 변수 경로보다
 * 먼저 고른다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/districts")
@Tag(name = "행정동 주간 집계", description = "행정동 × 주 시민 자가보고 집계 공개 조회 API")
public class AggregateWebController {

    private final AggregateWebUseCase aggregateWebUseCase;

    @Operation(summary = "동네 주간 집계 (홈)", description = """
        행정동 하나의 한 주 집계를 돌려줍니다. 단계(`level`)는 NORMAL · SLIGHT · HIGH · INSUFFICIENT 입니다.
        **INSUFFICIENT(자료 부족)면 `symptomaticCount` · `symptomRate` · `baselineRate` · `groups` 가 응답에 없습니다** — 화면은 수치 · 상태색을 그리지 않습니다.
        `insufficientReason`(LOW_SAMPLE · UNSTABLE · NO_BASELINE)은 있지만 셋 모두 같은 자료 부족으로 그립니다. 그 주 보고가 한 번도 없던 동은
        INSUFFICIENT · LOW_SAMPLE · 참여 0 입니다. 폐지된 코드도 200 이고 `district.active=false` 입니다.

        비율은 퍼센트 소수 첫째 자리 반올림입니다. 증상군 변화(`trend`)는 이번 주와 전주가 모두 수치가 있는 단계일 때만 있습니다.

        인증 불필요. **필수: code(경로, SGIS 행정동 코드 숫자 8자리)** — 형식이 틀리면 AGGREGATE_101, 없는 코드는 AGGREGATE_001(404) 입니다.
        **선택: week(YYYY-Www, 생략하면 현재 주 KST)** — 형식이 틀리면 AGGREGATE_102, 미래 주 · 51주보다 이전 · 그 해에 없는 주는 AGGREGATE_002(400) 입니다.

        호출 예: `GET /api/v1/districts/11440660/weekly-aggregate?week=2026-W41`""")
    @GetMapping("/{code}/weekly-aggregate")
    public ResponseEntity<Response<DistrictWeeklyAggregateResponse>> getDistrictWeeklyAggregate(
        @Parameter(description = "[필수] SGIS 행정동 코드 숫자 8자리", required = true, example = "11440660")
        @PathVariable @Pattern(regexp = AggregateValidationMessage.DISTRICT_CODE_REGEXP, message = AggregateValidationMessage.DISTRICT_CODE_FORMAT_INVALID) String code,
        @Parameter(description = "[선택] 집계 주 YYYY-Www. 생략하면 현재 주(KST). 현재 주 ~ 51주 전만", example = "2026-W41")
        @RequestParam(required = false) @Pattern(regexp = AggregateValidationMessage.ISO_WEEK_REGEXP, message = AggregateValidationMessage.ISO_WEEK_FORMAT_INVALID) String week) {
        return ResponseEntity.ok(Response.success(aggregateWebUseCase.getDistrictWeeklyAggregate(code, week)));
    }

    @Operation(summary = "시군구 동네별 주간 집계 (지도)", description = """
        시군구 하나의 **현행** 행정동 전부의 한 주 집계를 코드 오름차순으로 돌려줍니다. 동네마다 값의 모양은 동네 주간 집계의 `district` 와 같고,
        자료 부족 동네는 수치가 없습니다. 집계 행이 없는 동도 자료 부족(참여 0)으로 담깁니다.

        **include(찾은 동네)는 반드시 담습니다** — 시군구 밖이거나 폐지된 코드여도 목록 맨 앞에 더합니다(이미 목록에 있으면 그 자리 그대로).
        시군구에 현행 동이 없고 include 도 없으면 `districts` 는 빈 배열입니다.

        인증 불필요. **필수: sigunguCode(숫자 5자리)** — 없으면 AGGREGATE_103, 형식이 틀리면 AGGREGATE_104.
        **선택: include(행정동 코드 8자리)** — 형식이 틀리면 AGGREGATE_101, 없는 코드는 AGGREGATE_001(404).
        **선택: week** — 동네 주간 집계와 같습니다(AGGREGATE_102 · AGGREGATE_002).

        호출 예: `GET /api/v1/districts/weekly-aggregates?sigunguCode=11440&include=11440660`""")
    @GetMapping("/weekly-aggregates")
    public ResponseEntity<Response<DistrictWeeklyAggregateListResponse>> getDistrictWeeklyAggregates(
        @ParameterObject @Valid @ModelAttribute DistrictWeeklyAggregateListRequest request) {
        return ResponseEntity.ok(Response.success(aggregateWebUseCase.getDistrictWeeklyAggregates(request.sigunguCode(), request.include(), request.week())));
    }
}
