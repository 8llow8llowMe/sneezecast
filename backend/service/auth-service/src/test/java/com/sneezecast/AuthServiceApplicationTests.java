package com.sneezecast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sneezecast.domainlayer.auth.adapter.in.web.dto.item.AuthSessionItem;
import com.sneezecast.domainlayer.auth.adapter.out.persistence.RedisAccessTokenBlacklistAdapter;
import com.sneezecast.domainlayer.auth.application.port.out.MailSendPort;
import com.sneezecast.global.properties.AuthSessionProperties;
import com.sneezecast.global.properties.LoginAttemptProperties;
import com.sneezecast.global.properties.PasswordResetProperties;
import com.sneezecast.persistence.util.SnowflakeIdGenerator;
import com.sneezecast.security.auth.blacklist.AccessTokenBlacklistVerifier;
import com.sneezecast.security.auth.jwt.JwtAuthProvider;
import com.sneezecast.security.common.enums.SecurityRole;
import com.sneezecast.storage.init.StorageBucketInitializer;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.time.Duration;
import java.time.Instant;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisNode;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 기본 프로파일(dev)로 컨텍스트가 뜨고, security-core 필터 체인이 블랙리스트 어댑터를 실제로 물고 도는지 본다.
 *
 * <p>local 프로파일이 없으므로 dev 설정을 그대로 쓰고, 배포 때 env 로 들어오는 값만 여기서 채운다. 외부에는 붙지 않는다.
 * <ul>
 *   <li>MySQL → H2 인메모리 (드라이버 · URL 만 바꾼다).</li>
 *   <li>Eureka → 클라이언트를 끈다.</li>
 *   <li>Redis → 연결은 첫 명령까지 미뤄지고, 명령을 내는 {@link StringRedisTemplate} 은 mock 으로 바꾼다.</li>
 *   <li>SMTP → 계정 값만 채우고, {@code JavaMailSender} 는 mock 으로 바꿔 발송 스레드만 본다.</li>
 *   <li>MinIO → 클라이언트 생성은 접속하지 않지만, {@link StorageBucketInitializer} 가 기동 완료 이벤트에서 버킷을 확인하러
 *       접속하므로 mock 으로 바꾼다.</li>
 * </ul>
 * mock 은 {@code enforceOverride} 로 <b>원래 빈이 있을 때만</b> 바꾼다 — core 설정 {@code @Import} 가 빠져 빈이 없으면
 * mock 이 새로 생겨 배선 누락을 가리는 대신 여기서 실패한다.
 *
 * <p>URL 수준은 security-core 가 전부 열어 두므로 보호는 {@code @PreAuthorize} 에만 달려 있다. 테스트 전용 컨트롤러
 * ({@link MethodSecurityProbeController})로 메서드 보안이 실제로 켜져 있는지도 본다.
 */
@SpringBootTest(properties = {
    "spring.profiles.active=dev",
    "eureka.client.enabled=false",
    "AUTH_SERVICE_PORT=0",
    "SERVICE_DISCOVERY_HOSTNAME=localhost",
    "SERVICE_DISCOVERY_PORT=8761",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "AUTH_DB_URL=jdbc:h2:mem:auth-context;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "AUTH_DB_USERNAME=sa",
    "AUTH_DB_PASSWORD=",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "JWT_ACCESS_KEY=sneezecast-auth-context-test-access-secret-key-0123456789-0123456789",
    "JWT_REFRESH_KEY=sneezecast-auth-context-test-refresh-secret-key-0123456789-0123456789",
    "REDIS_MASTER_NAME=mymaster",
    "REDIS_SENTINEL_NODES=localhost:26379",
    "REDIS_PASSWORD=",
    "MINIO_ENDPOINT=http://localhost:9000",
    "MINIO_PUBLIC_URL=http://localhost:9000",
    "MINIO_BUCKET=sneezecast-context-test",
    "MINIO_ACCESS_KEY=context-test-access-key",
    "MINIO_SECRET_KEY=context-test-secret-key",
    "MAIL_USERNAME=context-test",
    "MAIL_PASSWORD=context-test-password"
})
@AutoConfigureMockMvc
@Import(AuthServiceApplicationTests.MethodSecurityProbeController.class)
class AuthServiceApplicationTests {

    private static final String BLACKLIST_KEY_PREFIX = "sneezecast:auth:accessTokenBlacklist:";

    @MockitoBean(enforceOverride = true)
    private StringRedisTemplate stringRedisTemplate;

    @MockitoBean(enforceOverride = true)
    private StorageBucketInitializer storageBucketInitializer;

    @MockitoBean(enforceOverride = true)
    private JavaMailSender javaMailSender;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtAuthProvider jwtAuthProvider;

    @Test
    @DisplayName("블랙리스트 계약의 구현은 Redis 어댑터 하나다 — 없으면 security-core 필터가 폐기 검사를 조용히 건너뛴다")
    void registersRedisBlacklistVerifier() {
        assertThat(context.getBeansOfType(AccessTokenBlacklistVerifier.class).values())
            .singleElement()
            .isInstanceOf(RedisAccessTokenBlacklistAdapter.class);
    }

    @Test
    @DisplayName("persistence-core 설정이 켜져 있다 — Snowflake ID · JPAQueryFactory · JPA Auditing. 빠지면 감사 컬럼이 비어 저장이 실패한다")
    void registersPersistenceCoreBeans() {
        assertThat(context.getBean(SnowflakeIdGenerator.class).generateId()).isPositive();
        assertThat(context.containsBean("jpaQueryFactory")).isTrue();
        assertThat(context.containsBean("jpaAuditingHandler")).isTrue();
    }

    @Test
    @DisplayName("기본 applicationTaskExecutor 와 메일 전용 executor 가 따로 있다 — 메일 executor 를 만들어도 Boot 기본 executor 가 사라지지 않는다")
    void keepsDefaultExecutorBesideMailExecutor() {
        assertThat(context.getBean("applicationTaskExecutor")).isNotSameAs(context.getBean("authMailTaskExecutor"));
    }

    @Test
    @DisplayName("인증 메일은 요청 스레드가 아니라 authMailTaskExecutor 스레드에서 보낸다 (@Async 가 실제로 켜져 있다)")
    void mailIsSentOnMailExecutorThread() throws Exception {
        when(javaMailSender.createMimeMessage()).thenAnswer(invocation -> new MimeMessage(Session.getInstance(new Properties())));
        CountDownLatch sent = new CountDownLatch(1);
        AtomicReference<String> senderThread = new AtomicReference<>();
        doAnswer(invocation -> {
            senderThread.set(Thread.currentThread().getName());
            sent.countDown();
            return null;
        }).when(javaMailSender).send(any(MimeMessage.class));

        context.getBean(MailSendPort.class).sendVerificationCode("user@example.com", "482913");

        assertThat(sent.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(senderThread.get()).startsWith("auth-mail-worker-").isNotEqualTo(Thread.currentThread().getName());
    }

    @Test
    @DisplayName("Redis 연결은 redis-core 의 Sentinel 구성이다 — import 가 빠지면 Boot 기본값(localhost standalone)으로 조용히 붙는다")
    void usesSentinelConnectionFromRedisCore() {
        assertThat(context.getBean(RedisConnectionFactory.class)).isInstanceOfSatisfying(LettuceConnectionFactory.class, factory -> {
            assertThat(factory.getSentinelConfiguration()).isNotNull();
            assertThat(factory.getSentinelConfiguration().getMaster().getName()).isEqualTo("mymaster");
            assertThat(factory.getSentinelConfiguration().getSentinels())
                .extracting(RedisNode::getHost, RedisNode::getPort)
                .containsExactly(tuple("localhost", 26379));
        });
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
    @DisplayName("USER 토큰으로 ADMIN 메서드를 부르면 403 SECURITY_006 봉투다")
    void userCallToAdminMethodIsForbidden() throws Exception {
        when(stringRedisTemplate.hasKey(anyString())).thenReturn(false);

        mockMvc.perform(get(MethodSecurityProbeController.ADMIN_PATH).header(HttpHeaders.AUTHORIZATION, bearer(SecurityRole.USER)))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.dataHeader.success").value(false))
            .andExpect(jsonPath("$.dataHeader.resultCode").value("SECURITY_006"));
    }

    @Test
    @DisplayName("ADMIN 토큰이면 ADMIN 메서드가 200 이다 — authority 는 ROLE_ 접두어 없이 역할 이름 그대로다")
    void adminCallToAdminMethodSucceeds() throws Exception {
        when(stringRedisTemplate.hasKey(anyString())).thenReturn(false);

        mockMvc.perform(get(MethodSecurityProbeController.ADMIN_PATH).header(HttpHeaders.AUTHORIZATION, bearer(SecurityRole.ADMIN)))
            .andExpect(status().isOk());
        mockMvc.perform(get(MethodSecurityProbeController.AUTHENTICATED_PATH).header(HttpHeaders.AUTHORIZATION, bearer(SecurityRole.ADMIN)))
            .andExpect(status().isOk());
    }

    @Test
    @DisplayName("토큰 없이 API 문서를 읽을 수 있다 — URL 수준 인가는 열어 두고 인가는 @PreAuthorize 가 한다")
    void apiDocsArePublic() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk());
    }

    @Test
    @DisplayName("폐기되지 않은 토큰은 필터를 통과하고, 조회 키는 게이트웨이와 같은 형식이다")
    void activeTokenPassesFilter() throws Exception {
        when(stringRedisTemplate.hasKey(anyString())).thenReturn(false);

        mockMvc.perform(get("/v3/api-docs").header(HttpHeaders.AUTHORIZATION, bearer()))
            .andExpect(status().isOk());

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(stringRedisTemplate).hasKey(key.capture());
        assertThat(key.getValue()).startsWith(BLACKLIST_KEY_PREFIX).hasSizeGreaterThan(BLACKLIST_KEY_PREFIX.length());
    }

    @Test
    @DisplayName("폐기된 토큰은 401 SECURITY_007 봉투로 끝난다")
    void revokedTokenIsRejected() throws Exception {
        when(stringRedisTemplate.hasKey(anyString())).thenReturn(true);

        mockMvc.perform(get("/v3/api-docs").header(HttpHeaders.AUTHORIZATION, bearer()))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.dataHeader.success").value(false))
            .andExpect(jsonPath("$.dataHeader.resultCode").value("SECURITY_007"));
    }

    @Test
    @DisplayName("Redis 장애면 503 SECURITY_008 봉투로 끝난다 — 500 이 아니다")
    void redisFailureIsServiceUnavailable() throws Exception {
        when(stringRedisTemplate.hasKey(anyString())).thenThrow(new RedisConnectionFailureException("down"));

        mockMvc.perform(get("/v3/api-docs").header(HttpHeaders.AUTHORIZATION, bearer()))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.dataHeader.success").value(false))
            .andExpect(jsonPath("$.dataHeader.resultCode").value("SECURITY_008"));
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({"POST, /api/v1/auth/logout", "GET, /api/v1/auth/sessions", "DELETE, /api/v1/auth/sessions",
        "DELETE, /api/v1/auth/sessions/3f2a9c11-0e4b-4a1f-9c3d-0b8e2f7a5d61"})
    @DisplayName("로그아웃 · 세션 API 는 토큰이 없으면 401 SECURITY_001 봉투다 — @PreAuthorize 가 실제로 걸려 있다")
    void sessionApisRequireAuthentication(String method, String path) throws Exception {
        MockHttpServletRequestBuilder request = switch (method) {
            case "POST" -> post(path);
            case "DELETE" -> delete(path);
            default -> get(path);
        };

        mockMvc.perform(request)
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("SECURITY_001"));
    }

    /**
     * 바디가 있는 API 는 올바른 바디를 싣는다 — 바디 역직렬화 · 검증은 메서드 보안보다 먼저라, 바디가 틀리면 401 대신 400 이 먼저 나온다.
     */
    @ParameterizedTest(name = "{0} {1}")
    @CsvSource(delimiter = '|', value = {
        "GET | /api/v1/members/me | ",
        "PATCH | /api/v1/members/me | {\"nickname\":\"재채기탐정\"}",
        "POST | /api/v1/members/me/password | {\"currentPassword\":\"P@ssw0rd!\",\"newPassword\":\"Sneeze2026!\"}",
        "POST | /api/v1/members/me/password/setup | {\"newPassword\":\"Sneeze2026!\"}"})
    @DisplayName("내 정보 · 비밀번호 변경 · 설정 API 는 토큰이 없으면 401 SECURITY_001 봉투다 — @PreAuthorize 가 실제로 걸려 있다")
    void memberApisRequireAuthentication(String method, String path, String body) throws Exception {
        MockHttpServletRequestBuilder request = switch (method) {
            case "POST" -> post(path);
            case "PATCH" -> patch(path);
            default -> get(path);
        };
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }

        mockMvc.perform(request)
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("SECURITY_001"));
    }

    @Test
    @DisplayName("비밀번호 재설정 API 는 인증 없이 열려 있다 — 토큰 없이 불러도 401 이 아니라 요청 검증(AUTH_115)까지 간다")
    void passwordResetIsPublic() throws Exception {
        mockMvc.perform(post("/api/v1/auth/password/reset").contentType(MediaType.APPLICATION_JSON).content("{\"newPassword\":\"Sneeze2026!\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("AUTH_115"));
    }

    @Test
    @DisplayName("member 컨트롤러의 검증 오류도 MEMBER 대역 봉투다 — member 전용 advice 가 실제로 등록돼 있다")
    void memberValidationIsEnveloped() throws Exception {
        when(stringRedisTemplate.hasKey(anyString())).thenReturn(false);

        mockMvc.perform(patch("/api/v1/members/me").header(HttpHeaders.AUTHORIZATION, bearer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"nickname\":\"탐\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("MEMBER_102"));
    }

    @Test
    @DisplayName("비밀번호 재설정 토큰 수명 기본값(15분)이 바인딩된다")
    void bindsPasswordResetDefaults() {
        assertThat(context.getBean(PasswordResetProperties.class)).isEqualTo(new PasswordResetProperties(Duration.ofMinutes(15)));
    }

    @Test
    @DisplayName("토큰 재발급은 인증 없이 열려 있고, 쿠키가 없으면 SECURITY_001 이 아니라 AUTH_014 다")
    void reissueIsPublicAndRequiresCookie() throws Exception {
        mockMvc.perform(post("/api/v1/auth/token/reissue"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("AUTH_014"));
    }

    @Test
    @DisplayName("응답 시각(Instant)은 숫자 타임스탬프가 아니라 ISO-8601 UTC 문자열로 나간다 — 앱의 ObjectMapper 기준")
    void instantsAreSerializedAsIsoStrings() throws Exception {
        String json = context.getBean(ObjectMapper.class).writeValueAsString(AuthSessionItem.builder().sessionId("s")
            .createdAt(Instant.parse("2026-10-01T00:30:00Z")).lastUsedAt(Instant.parse("2026-10-01T05:12:00Z")).build());

        assertThat(json).contains("\"createdAt\":\"2026-10-01T00:30:00Z\"").contains("\"lastUsedAt\":\"2026-10-01T05:12:00Z\"");
    }

    @Test
    @DisplayName("로그인 시도 제한 · 세션 설정의 기본값이 바인딩된다 (5회 · 10분 · IP 30회/1시간 · 기기 5대 · 유예 10초)")
    void bindsLoginAndSessionDefaults() {
        assertThat(context.getBean(LoginAttemptProperties.class)).isEqualTo(new LoginAttemptProperties(5, Duration.ofMinutes(10), 30, Duration.ofHours(1)));
        assertThat(context.getBean(AuthSessionProperties.class)).isEqualTo(new AuthSessionProperties(5, Duration.ofSeconds(10)));
    }

    private String bearer() {
        return bearer(SecurityRole.USER);
    }

    private String bearer(SecurityRole role) {
        return "Bearer " + jwtAuthProvider.issueAccessToken(1L, role, Set.of(), null).value();
    }

    /** 메서드 보안 확인용. 테스트 소스에만 있고 {@code @Import} 로만 올라간다. */
    @RestController
    static class MethodSecurityProbeController {

        static final String AUTHENTICATED_PATH = "/test/method-security/authenticated";
        static final String ADMIN_PATH = "/test/method-security/admin";

        @GetMapping(AUTHENTICATED_PATH)
        @PreAuthorize("isAuthenticated()")
        public String authenticated() {
            return "ok";
        }

        @GetMapping(ADMIN_PATH)
        @PreAuthorize("hasAuthority('ADMIN')")
        public String admin() {
            return "ok";
        }
    }
}
