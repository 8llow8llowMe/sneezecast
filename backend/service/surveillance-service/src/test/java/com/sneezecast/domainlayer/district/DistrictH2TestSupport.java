package com.sneezecast.domainlayer.district;

import com.sneezecast.SurveillanceH2TestSupport;

/**
 * 행정동 H2 테스트 공통 헬퍼. 컨텍스트 구성은 {@link SurveillanceH2TestSupport} 를 그대로 쓴다.
 *
 * <p>행은 JDBC 로 넣는다 — 실제로도 batch-service 가 JDBC upsert 로 쓰고, surveillance 리포지토리에는 저장 메서드가 없다.
 */
public abstract class DistrictH2TestSupport extends SurveillanceH2TestSupport {

    protected static final Short RETIRED_IN_2024 = 2024;

    protected void clearDistricts() {
        jdbcTemplate.update("DELETE FROM district");
    }

    /** 현행 행정동. id 는 batch 처럼 코드를 숫자로 바꾼 값이다. */
    protected void insertActive(String code, String name, String sidoName, String sigunguName) {
        insert(code, name, sidoName, sigunguName, null);
    }

    protected void insert(String code, String name, String sidoName, String sigunguName, Short validToYear) {
        jdbcTemplate.update("""
                INSERT INTO district (id, code, name, sido_code, sido_name, sigungu_code, sigungu_name,
                                      valid_from_year, valid_to_year, last_seen_year, synced_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 2024, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)""",
            Long.parseLong(code), code, name, code.substring(0, 2), sidoName, code.substring(0, 5), sigunguName,
            validToYear, validToYear == null ? 2025 : validToYear);
    }
}
