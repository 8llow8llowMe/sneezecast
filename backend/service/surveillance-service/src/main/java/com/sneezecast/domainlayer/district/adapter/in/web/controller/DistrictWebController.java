package com.sneezecast.domainlayer.district.adapter.in.web.controller;

import com.sneezecast.common.dto.Response;
import com.sneezecast.domainlayer.district.adapter.in.web.dto.item.DistrictSearchItem;
import com.sneezecast.domainlayer.district.adapter.in.web.dto.request.DistrictSearchRequest;
import com.sneezecast.domainlayer.district.adapter.in.web.dto.response.DistrictDetailResponse;
import com.sneezecast.domainlayer.district.application.exception.DistrictValidationMessage;
import com.sneezecast.domainlayer.district.application.port.in.DistrictWebUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 행정동 공개 API. 비로그인 둘러보기에서도 동네를 고르므로 {@code @PreAuthorize} 를 달지 않는다 (URL 수준은 security-core 가 전부 열어 둔다).
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/districts")
@Tag(name = "행정동", description = "행정동 검색 · 단건 조회 API")
public class DistrictWebController {

    private final DistrictWebUseCase districtWebUseCase;

    /**
     * 목록은 {@code SliceResponse} 가 기본이지만(architecture-guide §8) 자동완성 검색이라 다음 페이지가 없고 프론트 계약
     * ({@code searchDistricts(): District[]})이 배열이라, {@code dataBody} 를 배열로 두고 상한(20건)에서 자른다.
     */
    @Operation(summary = "행정동 검색", description = """
        동 이름 또는 시도 · 시군구 이름에 검색어가 들어간 **현행** 행정동을 코드 오름차순으로 최대 20건 돌려줍니다.
        `서울특별시 강남구` 처럼 시도와 시군구를 이어 쓴 검색어도 찾습니다. 폐지된 동은 나오지 않습니다.
        `%` · `_` 는 와일드카드가 아니라 글자 그대로 찾습니다. 일치하는 동이 없으면 빈 배열입니다.

        인증 불필요. **필수: query(앞뒤 공백을 뺀 1~20자).** 비었으면 DISTRICT_101, 20자를 넘으면 DISTRICT_102(400) 입니다.

        호출 예: `GET /api/v1/districts?query=역삼`""")
    @GetMapping
    public ResponseEntity<Response<List<DistrictSearchItem>>> searchDistricts(@ParameterObject @Valid @ModelAttribute DistrictSearchRequest request) {
        return ResponseEntity.ok(Response.success(districtWebUseCase.searchDistricts(request.query())));
    }

    @Operation(summary = "행정동 단건 조회", description = """
        코드로 행정동 하나를 돌려줍니다. 폐지된 코드도 200 이고 `active=false` 입니다 — 고른 동네가 폐지됐으면 화면에서 다시 고르게 합니다.
        없는 코드는 DISTRICT_001(404) 입니다.

        인증 불필요. **필수: code(경로, SGIS 행정동 코드 숫자 8자리).** 형식이 틀리면 DISTRICT_103(400) 입니다.

        호출 예: `GET /api/v1/districts/11230510`""")
    @GetMapping("/{code}")
    public ResponseEntity<Response<DistrictDetailResponse>> getDistrict(
        @Parameter(description = "[필수] SGIS 행정동 코드 숫자 8자리", required = true, example = "11230510")
        @PathVariable @Pattern(regexp = DistrictValidationMessage.DISTRICT_CODE_REGEXP, message = DistrictValidationMessage.DISTRICT_CODE_FORMAT_INVALID) String code) {
        return ResponseEntity.ok(Response.success(districtWebUseCase.getDistrict(code)));
    }
}
