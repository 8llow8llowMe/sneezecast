package com.sneezecast.domainlayer.district.adapter.in.web.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

@Builder
@Schema(description = "행정동 단건 응답 DTO")
public record DistrictDetailResponse(
    @Schema(description = "SGIS 행정동 코드 8자리", example = "11230510")
    String code,

    @Schema(description = "행정동 이름", example = "역삼1동")
    String name,

    @Schema(description = "시도 · 시군구 표기. 시군구가 없는 시도는 시도 이름만", example = "서울특별시 강남구")
    String sigungu,

    @Schema(description = "현행 행정동인지. false 면 폐지된 코드라 화면에서 다시 고르게 한다", example = "true")
    boolean active
) {

}
