package com.sneezecast.domainlayer.official.domain.model;

import java.util.Objects;

/**
 * 쓰기 직전에 Snowflake id 를 붙인 {@link OfficialRecord}.
 *
 * <p>{@code id} 는 새 행일 때만 쓰인다. 같은 자연키 행이 이미 있으면 기존 id 가 남고 이 값은 버려진다 — 재적재해도 행 id 가 바뀌지 않는다.
 *
 * @param id 새 행이면 쓸 PK (Snowflake)
 */
public record IdentifiedOfficialRecord(long id, OfficialRecord record) {

    public IdentifiedOfficialRecord {
        Objects.requireNonNull(record, "record");
    }
}
