package com.sneezecast.domainlayer.official.domain.enums;

/**
 * 원천을 받은 경로 (official_source_snapshot {@code channel}). OPEN_API 는 공공데이터포털, PORTAL_JSON 은 감염병포털 화면 데이터다.
 *
 * <p>surveillance-service 의 같은 이름 enum 을 복제한 것이다 (서비스 간 도메인 코드를 공유하지 않는다, entity-design §7).
 * <b>저장 값 {@code name()} 이 DB 계약</b>이라 이름 · 값을 surveillance 와 함께 바꾼다. batch 는 화면이 없어 표시 이름을 두지 않는다.
 */
public enum IngestChannel {
    OPEN_API,
    PORTAL_JSON
}
