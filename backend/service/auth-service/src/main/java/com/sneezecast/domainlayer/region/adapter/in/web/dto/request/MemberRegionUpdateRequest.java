package com.sneezecast.domainlayer.region.adapter.in.web.dto.request;

import com.sneezecast.domainlayer.region.application.exception.RegionValidationMessage;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

@Schema(description = "내 동네 저장 요청 DTO")
public record MemberRegionUpdateRequest(
    @Schema(description = "행정동 검색 결과의 SGIS 행정동 코드 (숫자 8자리). 현행 행정동만 저장할 수 있다", example = "11230510",
        requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = RegionValidationMessage.DISTRICT_CODE_REQUIRED)
    @Pattern(regexp = RegionValidationMessage.DISTRICT_CODE_REGEXP, message = RegionValidationMessage.DISTRICT_CODE_FORMAT_INVALID)
    String code
) {

}
