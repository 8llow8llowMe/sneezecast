package com.sneezecast.global.config;

import com.sneezecast.common.config.JasyptConfigurer;
import com.sneezecast.persistence.config.JpaAuditConfig;
import com.sneezecast.persistence.config.QuerydslConfigurer;
import com.sneezecast.persistence.config.SnowflakeConfigurer;
import com.sneezecast.security.resourceserver.config.ResourceServerSecurityConfigurer;
import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * core 모듈의 빈 등록을 명시한다. core 패키지는 컴포넌트 스캔 대상이 아니므로 여기서 빠지면 빈이 없다.
 *
 * <p>보안은 security-core 의 {@code resourceserver} 구성이다 — 토큰을 발급하지 않고 auth 가 발급한 access token 을 검증만 한다.
 * {@code @EnableMethodSecurity} 도 이 구성이 켠다. auth 쪽 {@code AuthSecurityConfigurer} 를 함께 올리면 필터 체인이 둘이 된다.
 *
 * <p>JPA Auditing 은 {@code BaseEntity} 의 생성 · 수정 시각 컬럼용이다. QueryDSL({@code JPAQueryFactory})은 행정동 검색 같은 동적 조회용이다.
 * persistence-core 의 Snowflake 생성기는 {@code weekly_report} 처럼 이 서비스가 직접 만드는 행의 PK 용이다 (district · official 은 batch 가
 * id 를 할당한다). 설정 키는 {@code snowflake.*} — {@code SurveillanceServicePropertiesConfig} 가 묶는다.
 */
@Configuration
@Import({
    JasyptConfigurer.class,
    ResourceServerSecurityConfigurer.class,
    JpaAuditConfig.class,
    QuerydslConfigurer.class,
    SnowflakeConfigurer.class
})
public class SurveillanceServiceBeansConfig {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /**
     * 현재 시각의 단일 출처. 보고 주 계산({@code ReportWeekCalculator})과 보고 수정 시각이 이 빈을 쓴다 — 테스트가 고정 Clock 으로 바꿔 끼워
     * 주 경계를 재현할 수 있게 {@code now()} 를 코드에 흩어 두지 않는다. zone 은 KST 지만 주 계산은 zone 과 무관하게 KST 로 한다.
     */
    @Bean
    public Clock clock() {
        return Clock.system(KST);
    }
}
