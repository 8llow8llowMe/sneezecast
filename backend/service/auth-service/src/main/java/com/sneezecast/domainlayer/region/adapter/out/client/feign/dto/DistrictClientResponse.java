package com.sneezecast.domainlayer.region.adapter.out.client.feign.dto;

/**
 * surveillance {@code GET /internal/v1/districts/{code}} 의 {@code dataBody} ({@code DistrictInternalResponse}). surveillance 클래스를 공유하지
 * 않고 auth 쪽에 따로 둔다 — 서비스 간 계약은 JSON 필드 이름이고, 모르는 필드가 늘어도 깨지지 않는다(Boot 기본 ObjectMapper 는
 * 알 수 없는 속성을 무시한다).
 *
 * @param active 현행 행정동인지. 폐지된 코드도 200 에 false 로 온다
 */
public record DistrictClientResponse(
    String code,
    String name,
    String sigungu,
    boolean active
) {

}
