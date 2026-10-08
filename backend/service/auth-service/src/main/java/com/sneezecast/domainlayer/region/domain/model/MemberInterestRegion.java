package com.sneezecast.domainlayer.region.domain.model;

import lombok.Builder;

/**
 * 회원이 내 동네 말고 지켜보려고 고른 동네(행정동) 하나 — 회원당 여러 행이다 (entity-design §1-6). 이름 · 폐지 여부는 내 동네처럼 surveillance
 * {@code district} 가 정본이라 여기 두지 않는다.
 *
 * @param districtCode SGIS 행정동 코드 8자리. 고를 때 현행 코드였다 — 그 뒤 폐지돼도 자동으로 지우거나 바꾸지 않는다
 * @param slot         회원당 상한을 DB unique 로 지키기 위한 칸 번호(1..{@code region.interest.max-count}). 순서가 아니다 — 고른 순서는
 *                     {@code id}(Snowflake, 시간순)이고, 지운 칸은 다음에 고른 동네가 다시 쓴다
 */
@Builder
public record MemberInterestRegion(
    long id,
    long memberId,
    String districtCode,
    int slot
) {

}
