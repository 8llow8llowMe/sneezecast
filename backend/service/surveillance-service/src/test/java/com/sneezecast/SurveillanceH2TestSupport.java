package com.sneezecast;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * surveillance H2 테스트 공통 구성 (dev 프로파일, H2 MySQL 모드, 외부 연결 없음). 이 클래스를 상속한 테스트끼리는 설정이 같아 스프링 컨텍스트
 * 하나를 함께 쓴다 — 컨텍스트별 설정을 테스트마다 따로 두면 컨텍스트가 그만큼 더 뜬다. 행 정리는 각 테스트가 자기 테이블만 한다.
 *
 * <p>컨텍스트 구성은 {@code SurveillanceServiceApplicationTests} 와 같다. 컨텍스트 자체를 검사하는 그 테스트와 엔티티별 스키마 테스트 일부는
 * 일부러 따로 띄운다.
 *
 * <p>보안 필터를 거치는 테스트는 {@link JwtTestTokens#bearer} 에 {@link #ACCESS_KEY} 를 넘겨 토큰을 만든다.
 */
@SpringBootTest(properties = {
    "spring.profiles.active=dev",
    "eureka.client.enabled=false",
    "SURVEILLANCE_SERVICE_PORT=0",
    "SERVICE_DISCOVERY_HOSTNAME=localhost",
    "SERVICE_DISCOVERY_PORT=8761",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "SURVEILLANCE_DB_URL=jdbc:h2:mem:surveillance-h2;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "SURVEILLANCE_DB_USERNAME=sa",
    "SURVEILLANCE_DB_PASSWORD=",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "JWT_ACCESS_KEY=" + SurveillanceH2TestSupport.ACCESS_KEY,
    "REPORTER_KEY_PEPPER=sneezecast-surveillance-h2-test-pepper-0123456789"
})
@AutoConfigureMockMvc
public abstract class SurveillanceH2TestSupport {

    /** 이 컨텍스트의 {@code JWT_ACCESS_KEY}. */
    protected static final String ACCESS_KEY = "sneezecast-surveillance-h2-test-access-key-0123456789-0123456789-0123456789";

    @Autowired
    protected JdbcTemplate jdbcTemplate;
}
