package com.sneezecast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sneezecast.domainlayer.report.application.service.ReporterKeyGenerator;
import com.sneezecast.security.auth.jwt.JwtAuthProvider;
import com.sneezecast.security.common.constant.SecurityScope;
import com.sneezecast.security.common.enums.SecurityRole;
import com.sneezecast.security.common.exception.SecurityErrorCode;
import com.sneezecast.security.resourceserver.jwt.JwtToMemberConverter;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 기본 프로파일(dev)로 컨텍스트가 뜨고, security-core {@code resourceserver} 구성이 auth 가 발급한 토큰을 실제로 검증하는지 본다.
 *
 * <p>local 프로파일이 없으므로 dev 설정을 그대로 쓰고, 배포 때 env 로 들어오는 값만 여기서 채운다. 외부에는 붙지 않는다.
 * <ul>
 *   <li>MySQL → H2 인메모리 (드라이버 · URL 만 바꾼다).</li>
 *   <li>Eureka → 클라이언트를 끈다.</li>
 * </ul>
 * Redis · MinIO 를 쓰지 않으므로 바꿔 끼울 외부 클라이언트 빈이 없다.
 *
 * <p>토큰은 auth-service 와 같은 발급 코드(security-core {@link JwtAuthProvider}, {@link JwtTestTokens})로 같은 access key 에 서명한다. 발급 쪽과 검증 쪽의
 * 알고리즘(HS512) · 키 바이트 · claim(sub / role / scope / jti) 해석이 어긋나면 여기서 깨진다.
 *
 * <p>URL 수준은 security-core 가 전부 열어 두므로 보호는 {@code @PreAuthorize} 에만 달려 있다. 테스트 전용 컨트롤러
 * ({@link MethodSecurityProbeController})로 메서드 보안이 실제로 켜져 있는지 본다.
 */
@SpringBootTest(properties = {
    "spring.profiles.active=dev",
    "eureka.client.enabled=false",
    "SURVEILLANCE_SERVICE_PORT=0",
    "SERVICE_DISCOVERY_HOSTNAME=localhost",
    "SERVICE_DISCOVERY_PORT=8761",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "SURVEILLANCE_DB_URL=jdbc:h2:mem:surveillance-context;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "SURVEILLANCE_DB_USERNAME=sa",
    "SURVEILLANCE_DB_PASSWORD=",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "JWT_ACCESS_KEY=" + SurveillanceServiceApplicationTests.ACCESS_KEY,
    "REPORTER_KEY_PEPPER=" + SurveillanceServiceApplicationTests.PEPPER
})
@AutoConfigureMockMvc
@Import(SurveillanceServiceApplicationTests.MethodSecurityProbeController.class)
class SurveillanceServiceApplicationTests {

    static final String ACCESS_KEY = "sneezecast-surveillance-context-test-access-secret-key-0123456789-0123456789";
    /** ReporterKeyGeneratorTest 의 고정 벡터와 같은 pepper 다. */
    static final String PEPPER = "sneezecast-reporter-key-test-pepper-0123456789";
    private static final String OTHER_ACCESS_KEY = "sneezecast-surveillance-context-test-other-secret-key-0123456789-0123456789";

    @Autowired
    private ApplicationContext context;

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("보안 구성은 resourceserver 하나다 — 필터 체인 하나, 회원 변환기는 JwtToMemberConverter")
    void usesResourceServerSecurity() {
        assertThat(context.getBeansOfType(SecurityFilterChain.class)).hasSize(1);
        assertThat(context.getBeansOfType(JwtToMemberConverter.class)).hasSize(1);
    }

    @Test
    @DisplayName("REPORTER_KEY_PEPPER 가 surveillance.reporter-key.pepper 로 묶여 고정 벡터와 같은 키를 낸다")
    void bindsPepperFromEnvironment() {
        assertThat(context.getBean(ReporterKeyGenerator.class).reporterKey(1234567890123L))
            .isEqualTo("4b42a87be0f41fcdc70da95c1bdfa39b2a9610d5da1afdba4dddeb3e055c86a9");
    }

    @Test
    @DisplayName("토큰 없이 인증 필요 메서드를 부르면 401 SECURITY_001 봉투다 — @EnableMethodSecurity 가 빠지면 200 이 된다")
    void anonymousCallToAuthenticatedMethodIsUnauthorized() throws Exception {
        mockMvc.perform(get(MethodSecurityProbeController.AUTHENTICATED_PATH))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.dataHeader.success").value(false))
            .andExpect(jsonPath("$.dataHeader.resultCode").value("SECURITY_001"));
    }

    @Test
    @DisplayName("다른 키로 서명한 토큰은 401 봉투다 — 검증 키가 JWT_ACCESS_KEY 에서 온다")
    void tokenSignedWithOtherKeyIsUnauthorized() throws Exception {
        String token = JwtTestTokens.provider(OTHER_ACCESS_KEY).issueAccessToken(1L, SecurityRole.USER, Set.of(SecurityScope.REPORT_WRITE), null).value();

        mockMvc.perform(get(MethodSecurityProbeController.AUTHENTICATED_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.dataHeader.success").value(false))
            .andExpect(jsonPath("$.dataHeader.resultCode").value("SECURITY_004"));
    }

    /**
     * 서명은 맞는데 claim 이 발급 규약과 다른 토큰. {@link JwtAuthProvider} 로는 만들 수 없어서 jjwt 로 직접 서명한다.
     * {@link JwtToMemberConverter} 가 거부하지 못하고 런타임 예외를 흘리면 봉투 없는 500 이 된다.
     */
    static Stream<Arguments> tokensViolatingClaimContract() {
        return Stream.of(
            Arguments.of("sub 가 숫자가 아님", Map.of("sub", "abc", "role", "USER")),
            Arguments.of("role 이 없는 역할", Map.of("sub", "1", "role", "ROOT")),
            Arguments.of("scope 가 배열", Map.of("sub", "1", "role", "USER", "scope", List.of(SecurityScope.REPORT_WRITE)))
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("tokensViolatingClaimContract")
    @DisplayName("claim 규약을 어긴 토큰은 500 이 아니라 401 TOKEN_INVALID 봉투다")
    void tokenViolatingClaimContractIsUnauthorized(String description, Map<String, Object> claims) throws Exception {
        String token = signDirectly(claims, Instant.now().plus(Duration.ofMinutes(5)));

        mockMvc.perform(get(MethodSecurityProbeController.AUTHENTICATED_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.dataHeader.success").value(false))
            .andExpect(jsonPath("$.dataHeader.resultCode").value(SecurityErrorCode.TOKEN_INVALID.getCode()));
    }

    @Test
    @DisplayName("만료된 토큰은 401 TOKEN_EXPIRED 봉투다 — 검증기 기본 허용 오차(60초)보다 충분히 지난 만료 시각으로 본다")
    void expiredTokenIsUnauthorized() throws Exception {
        String token = signDirectly(Map.of("sub", "1", "role", "USER"), Instant.now().minus(Duration.ofMinutes(10)));

        mockMvc.perform(get(MethodSecurityProbeController.AUTHENTICATED_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.dataHeader.success").value(false))
            .andExpect(jsonPath("$.dataHeader.resultCode").value(SecurityErrorCode.TOKEN_EXPIRED.getCode()));
    }

    @Test
    @DisplayName("scope 없는 USER 토큰으로 보고 쓰기 메서드를 부르면 403 SECURITY_006 봉투다 — 민감정보 동의 전 회원")
    void userWithoutReportScopeIsForbidden() throws Exception {
        mockMvc.perform(get(MethodSecurityProbeController.REPORT_WRITE_PATH).header(HttpHeaders.AUTHORIZATION, bearer(SecurityRole.USER)))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.dataHeader.success").value(false))
            .andExpect(jsonPath("$.dataHeader.resultCode").value("SECURITY_006"));
    }

    @Test
    @DisplayName("report:write scope 가 있으면 보고 쓰기 메서드가 200 이다 — authority 는 SCOPE_report:write")
    void userWithReportScopeSucceeds() throws Exception {
        mockMvc.perform(get(MethodSecurityProbeController.REPORT_WRITE_PATH)
                .header(HttpHeaders.AUTHORIZATION, bearer(SecurityRole.USER, SecurityScope.REPORT_WRITE)))
            .andExpect(status().isOk());
        mockMvc.perform(get(MethodSecurityProbeController.AUTHENTICATED_PATH).header(HttpHeaders.AUTHORIZATION, bearer(SecurityRole.USER)))
            .andExpect(status().isOk());
    }

    @Test
    @DisplayName("USER 토큰으로 운영 메서드를 부르면 403 SECURITY_006 봉투다")
    void userCallToOperatorMethodIsForbidden() throws Exception {
        mockMvc.perform(get(MethodSecurityProbeController.OPERATOR_PATH)
                .header(HttpHeaders.AUTHORIZATION, bearer(SecurityRole.USER, SecurityScope.REPORT_WRITE)))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.dataHeader.success").value(false))
            .andExpect(jsonPath("$.dataHeader.resultCode").value("SECURITY_006"));
    }

    @Test
    @DisplayName("OPERATOR · ADMIN 토큰이면 운영 메서드가 200 이다 — authority 는 ROLE_ 접두어 없이 역할 이름 그대로다")
    void operatorAndAdminCallToOperatorMethodSucceeds() throws Exception {
        mockMvc.perform(get(MethodSecurityProbeController.OPERATOR_PATH).header(HttpHeaders.AUTHORIZATION, bearer(SecurityRole.OPERATOR)))
            .andExpect(status().isOk());
        mockMvc.perform(get(MethodSecurityProbeController.OPERATOR_PATH).header(HttpHeaders.AUTHORIZATION, bearer(SecurityRole.ADMIN)))
            .andExpect(status().isOk());
    }

    @Test
    @DisplayName("토큰 없이 API 문서를 읽을 수 있다 — URL 수준 인가는 열어 두고 인가는 @PreAuthorize 가 한다")
    void apiDocsArePublic() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk());
    }

    private static String bearer(SecurityRole role, String... scopes) {
        return JwtTestTokens.bearer(ACCESS_KEY, 1L, role, scopes);
    }

    private static String signDirectly(Map<String, Object> claims, Instant expiration) {
        Instant issuedAt = expiration.minus(Duration.ofMinutes(15));
        return Jwts.builder()
            .claims(claims)
            .id(UUID.randomUUID().toString())
            .issuedAt(Date.from(issuedAt))
            .expiration(Date.from(expiration))
            .signWith(Keys.hmacShaKeyFor(ACCESS_KEY.getBytes(StandardCharsets.UTF_8)), Jwts.SIG.HS512)
            .compact();
    }

    /** 메서드 보안 확인용. 테스트 소스에만 있고 {@code @Import} 로만 올라간다. */
    @RestController
    static class MethodSecurityProbeController {

        static final String AUTHENTICATED_PATH = "/test/method-security/authenticated";
        static final String REPORT_WRITE_PATH = "/test/method-security/report-write";
        static final String OPERATOR_PATH = "/test/method-security/operator";

        @GetMapping(AUTHENTICATED_PATH)
        @PreAuthorize("isAuthenticated()")
        public String authenticated() {
            return "ok";
        }

        @GetMapping(REPORT_WRITE_PATH)
        @PreAuthorize("hasAuthority('" + SecurityScope.REPORT_WRITE_AUTHORITY + "')")
        public String reportWrite() {
            return "ok";
        }

        @GetMapping(OPERATOR_PATH)
        @PreAuthorize("hasAnyAuthority('OPERATOR', 'ADMIN')")
        public String operator() {
            return "ok";
        }
    }
}
