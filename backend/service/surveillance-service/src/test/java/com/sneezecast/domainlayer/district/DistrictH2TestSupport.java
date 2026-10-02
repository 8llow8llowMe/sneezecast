package com.sneezecast.domainlayer.district;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 행정동 H2 테스트 공통 구성. 컨텍스트 구성은 {@code DistrictEntitySchemaTest} · {@code SurveillanceServiceApplicationTests} 와 같다
 * (dev 프로파일, H2 MySQL 모드, 외부 연결 없음). 이 클래스를 상속한 테스트끼리는 설정이 같아 컨텍스트 하나를 함께 쓴다.
 *
 * <p>행은 JDBC 로 넣는다 — 실제로도 batch-service 가 JDBC upsert 로 쓰고, surveillance 리포지토리에는 저장 메서드가 없다.
 */
@SpringBootTest(properties = {
    "spring.profiles.active=dev",
    "eureka.client.enabled=false",
    "SURVEILLANCE_SERVICE_PORT=0",
    "SERVICE_DISCOVERY_HOSTNAME=localhost",
    "SERVICE_DISCOVERY_PORT=8761",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "SURVEILLANCE_DB_URL=jdbc:h2:mem:surveillance-district-api;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "SURVEILLANCE_DB_USERNAME=sa",
    "SURVEILLANCE_DB_PASSWORD=",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "JWT_ACCESS_KEY=sneezecast-surveillance-district-api-test-access-key-0123456789-0123456789",
    "REPORTER_KEY_PEPPER=sneezecast-district-api-test-pepper-0123456789"
})
@AutoConfigureMockMvc
public abstract class DistrictH2TestSupport {

    protected static final Short RETIRED_IN_2024 = 2024;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

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
