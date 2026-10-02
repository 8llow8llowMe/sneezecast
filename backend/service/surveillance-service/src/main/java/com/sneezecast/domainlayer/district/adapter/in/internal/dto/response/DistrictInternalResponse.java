package com.sneezecast.domainlayer.district.adapter.in.internal.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

/**
 * 내부 API 행정동 응답. 지금은 공개 단건 응답과 필드가 같지만 타입을 나눠 둔다 — 공개 화면 계약이 바뀌어도 auth 의 Feign
 * {@code *ClientResponse} 가 기대하는 서비스 간 계약은 그대로 남게 한다.
 */
@Builder
@Schema(description = "행정동 코드 검증 응답 DTO (서비스 간)")
public record DistrictInternalResponse(
    @Schema(description = "SGIS 행정동 코드 8자리", example = "11230510")
    String code,

    @Schema(description = "행정동 이름", example = "역삼1동")
    String name,

    @Schema(description = "시도 · 시군구 표기", example = "서울특별시 강남구")
    String sigungu,

    @Schema(description = "현행 행정동인지. 회원 동네로 저장할 수 있는 것은 true 뿐이다", example = "true")
    boolean active
) {

}
