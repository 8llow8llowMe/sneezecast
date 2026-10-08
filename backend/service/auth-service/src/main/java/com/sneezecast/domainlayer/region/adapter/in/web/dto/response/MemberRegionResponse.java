package com.sneezecast.domainlayer.region.adapter.in.web.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

@Builder
@Schema(description = "동네 응답 DTO (내 동네 · 관심 동네 목록의 한 줄). 이름 · 폐지 여부는 조회할 때마다 행정동 마스터에서 다시 읽는다")
public record MemberRegionResponse(
    @Schema(description = "SGIS 행정동 코드 8자리. 주간 보고 요청에 이 값을 싣는다", example = "11230510")
    String code,

    @Schema(description = "행정동 이름. 행정동 마스터에서 코드를 찾지 못하면 null", example = "역삼1동")
    String name,

    @Schema(description = "시도 · 시군구 표기. 행정동 마스터에서 코드를 찾지 못하면 null", example = "서울특별시 강남구")
    String sigungu,

    @Schema(description = "고른 뒤 행정동이 폐지됐는지. true 면 내 동네는 다시 골라야 하고, 관심 동네는 지우게 한다 (서버는 자동으로 바꾸거나 지우지 않는다)",
        example = "false")
    boolean abolished
) {

}
