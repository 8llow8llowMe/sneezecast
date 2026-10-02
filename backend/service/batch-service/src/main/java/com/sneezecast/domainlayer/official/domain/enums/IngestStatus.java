package com.sneezecast.domainlayer.official.domain.enums;

/**
 * 수집 한 번의 결과 (official_source_snapshot {@code status}).
 *
 * <p>surveillance-service 의 같은 이름 enum 을 복제한 것이다 (서비스 간 도메인 코드를 공유하지 않는다, entity-design §7).
 * <b>저장 값 {@code name()} 이 DB 계약</b>이라 이름 · 값을 surveillance 와 함께 바꾼다. batch 는 화면이 없어 표시 이름을 두지 않는다.
 */
public enum IngestStatus {
    IMPORTED,
    FAILED
}
