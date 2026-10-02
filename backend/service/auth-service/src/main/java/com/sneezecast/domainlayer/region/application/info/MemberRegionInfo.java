package com.sneezecast.domainlayer.region.application.info;

import lombok.Builder;

/**
 * 내 동네. 이름 · 폐지 여부는 저장 값이 아니라 그때 surveillance 에서 읽은 값이다.
 *
 * @param name      행정동 이름. surveillance 에서 코드를 찾지 못하면 null
 * @param sigungu   시도 · 시군구 표기. surveillance 에서 코드를 찾지 못하면 null
 * @param abolished 지금은 현행 행정동이 아닌지 — 화면이 다시 고르게 한다
 */
@Builder
public record MemberRegionInfo(
    String code,
    String name,
    String sigungu,
    boolean abolished
) {

}
