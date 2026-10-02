package com.sneezecast.domainlayer.region.domain.model;

import lombok.Builder;

/**
 * 회원이 고른 동네(행정동) 하나 — 회원당 1행이다 (entity-design §1-4). 이름 · 폐지 여부는 surveillance {@code district} 가 정본이라 여기 두지
 * 않고, 조회할 때마다 내부 API 로 다시 읽는다.
 *
 * @param districtCode SGIS 행정동 코드 8자리. 저장할 때 현행 코드였다 — 그 뒤 폐지돼도 자동으로 바꾸지 않는다
 */
@Builder
public record MemberRegion(
    long id,
    long memberId,
    String districtCode
) {

}
