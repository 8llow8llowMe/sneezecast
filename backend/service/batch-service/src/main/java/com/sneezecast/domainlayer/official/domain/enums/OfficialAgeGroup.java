package com.sneezecast.domainlayer.official.domain.enums;

/**
 * 연령대 (official_surveillance {@code age_group}). 연령 구분이 없는 원천은 ALL 이다.
 *
 * <p>surveillance-service 의 같은 이름 enum 을 복제한 것이다 (서비스 간 도메인 코드를 공유하지 않는다, entity-design §7).
 * <b>저장 값 {@code name()} 이 DB 계약</b>이라 이름 · 값을 surveillance 와 함께 바꾼다. batch 는 화면이 없어 표시 이름을 두지 않는다.
 */
public enum OfficialAgeGroup {
    ALL,
    AGE_0,
    AGE_1_6,
    AGE_7_12,
    AGE_13_18,
    AGE_19_49,
    AGE_50_64,
    AGE_65_PLUS
}
