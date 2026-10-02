package com.sneezecast.domainlayer.region.application.port.out.query;

import lombok.Builder;

/**
 * surveillance 가 돌려준 행정동. Feign 응답 DTO({@code DistrictClientResponse})를 application 으로 들이지 않으려고 따로 둔다.
 *
 * @param sigungu 시도 · 시군구 표기 (예: {@code 서울특별시 강남구})
 * @param active  현행 행정동인지. 회원 동네로 저장할 수 있는 것은 true 뿐이다
 */
@Builder
public record DistrictQueryResult(
    String code,
    String name,
    String sigungu,
    boolean active
) {

}
