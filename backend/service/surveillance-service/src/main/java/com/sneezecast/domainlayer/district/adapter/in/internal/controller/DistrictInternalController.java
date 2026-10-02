package com.sneezecast.domainlayer.district.adapter.in.internal.controller;

import com.sneezecast.common.dto.Response;
import com.sneezecast.domainlayer.district.adapter.in.internal.dto.response.DistrictInternalResponse;
import com.sneezecast.domainlayer.district.application.exception.DistrictValidationMessage;
import com.sneezecast.domainlayer.district.application.port.in.DistrictInternalUseCase;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 서비스 간 행정동 API ({@code /internal/v1}, architecture-guide §4 고정 계약). 게이트웨이가 라우팅하지 않고 서비스 포트도 외부에 열지 않아
 * {@code @PreAuthorize} 없이 둔다.
 *
 * <p>응답은 공개 API 와 같은 {@code Response<T>} 봉투다 — 호출 쪽 Feign 헬퍼 {@code requestAndUnwrap}(coding-conventions §9)이 봉투를
 * 벗겨 {@code dataBody} 를 꺼내고, 4xx 는 {@code dataHeader.resultCode} 로 구분한다. 오류도 같은 advice({@code DistrictExceptionHandler})가 만든다.
 *
 * <p>공개 API 문서에 섞이면 프론트가 호출할 수 없는 경로를 보게 되므로 {@link Hidden} 으로 뺀다.
 */
@Hidden
@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/v1/districts")
public class DistrictInternalController {

    private final DistrictInternalUseCase districtInternalUseCase;

    @Operation(summary = "행정동 코드 검증 (auth → surveillance)", description = """
        auth 가 회원 동네를 저장하기 전에 부릅니다. 폐지된 코드도 200 이고 `active=false` 입니다 — 저장 거부는 호출자가 판단합니다.
        없는 코드는 DISTRICT_001(404), 형식이 틀리면 DISTRICT_103(400) 입니다.

        호출 예: `GET /internal/v1/districts/11230510`""")
    @GetMapping("/{code}")
    public ResponseEntity<Response<DistrictInternalResponse>> getDistrict(
        @Parameter(description = "[필수] SGIS 행정동 코드 숫자 8자리", required = true, example = "11230510")
        @PathVariable @Pattern(regexp = DistrictValidationMessage.DISTRICT_CODE_REGEXP, message = DistrictValidationMessage.DISTRICT_CODE_FORMAT_INVALID) String code) {
        return ResponseEntity.ok(Response.success(districtInternalUseCase.getDistrict(code)));
    }
}
