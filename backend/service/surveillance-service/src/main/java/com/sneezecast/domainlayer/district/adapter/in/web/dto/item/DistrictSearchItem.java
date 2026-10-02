package com.sneezecast.domainlayer.district.adapter.in.web.dto.item;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

/**
 * 행정동 검색 결과 한 줄. 모양은 프론트 {@code District} 타입({@code {code, name, sigungu}})과 같다.
 */
@Builder
@Schema(description = "행정동 검색 결과 항목")
public record DistrictSearchItem(
    @Schema(description = "SGIS 행정동 코드 8자리", example = "11230510")
    String code,

    @Schema(description = "행정동 이름", example = "역삼1동")
    String name,

    @Schema(description = "시도 · 시군구 표기. 시군구가 없는 시도는 시도 이름만", example = "서울특별시 강남구")
    String sigungu
) {

}
