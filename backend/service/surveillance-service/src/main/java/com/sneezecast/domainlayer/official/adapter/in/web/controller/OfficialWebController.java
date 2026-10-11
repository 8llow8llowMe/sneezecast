package com.sneezecast.domainlayer.official.adapter.in.web.controller;

import com.sneezecast.common.dto.Response;
import com.sneezecast.domainlayer.official.adapter.in.web.dto.response.OfficialLatestResponse;
import com.sneezecast.domainlayer.official.application.port.in.OfficialWebUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 공식 정보(질병관리청 감시 자료) 공개 API. 비회원도 보므로 {@code @PreAuthorize} 를 달지 않는다 (URL 수준은 security-core 가 전부 열어 둔다).
 * 시민 자가보고 집계와 다른 경로 · 다른 응답이다 — 한 응답에 섞지 않는다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/official")
@Tag(name = "공식 정보", description = "질병관리청 감시 자료 조회 API (시민 자가보고와 별도)")
public class OfficialWebController {

    private final OfficialWebUseCase officialWebUseCase;

    @Operation(summary = "질병관리청 감시 자료 최신 기준 주", description = """
        batch 가 적재한 질병관리청 감시 자료를 프로그램(전수신고 · 인플루엔자 의사환자 · 급성호흡기감염증 · 장관감염증) × 감염병(병원체)마다
        **가장 최근 기준 주** 하나씩 돌려줍니다. 무엇을 보일지는 화면이 고릅니다.

        - 출처(`source`) · 집계 단위(`regionLevel` · `regionName`, 지금은 전국뿐) · 기준 주(질병관리청 연도 · 주차와 시작 · 종료일) · 적재 시각(`syncedAt`)을 늘 싣습니다.
          기준 주는 질병관리청 주차(일요일 시작)라 시민 자가보고의 ISO 주와 다릅니다.
        - 값(`values[].value`)은 원값 그대로이고 단위는 `metric` 입니다. 원천이 빈 칸이면 null(0 과 다름)입니다.
        - 전수신고는 신고 지연 때문에 지금 주에서 2주 전(설정)보다 최근 주를 보이지 않습니다.
        - 적재가 실패 · 지연돼도 마지막으로 적재된 값을 그대로 주고 `syncedAt` 은 그 적재 시각입니다.
        - 발표 단계(유행주의보 등) · 쉬운 요약은 출처가 정해지지 않아 싣지 않습니다.
        - 적재된 자료가 없으면 `programs` 가 빈 배열입니다.

        인증 불필요. 파라미터 없음.

        호출 예: `GET /api/v1/official/latest`""")
    @GetMapping("/latest")
    public ResponseEntity<Response<OfficialLatestResponse>> getLatest() {
        return ResponseEntity.ok(Response.success(officialWebUseCase.getLatest()));
    }
}
