package com.sneezecast.domainlayer.region;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sneezecast.domainlayer.region.adapter.out.client.feign.DistrictClient;
import com.sneezecast.domainlayer.region.adapter.out.persistence.repository.MemberRegionRepository;
import com.sneezecast.domainlayer.region.application.port.out.DistrictQueryPort;
import com.sneezecast.domainlayer.region.application.port.out.query.DistrictQueryResult;
import com.sneezecast.global.client.FeignExceptionFixtures;
import com.sneezecast.global.client.InternalClientSupport;
import com.sneezecast.security.auth.jwt.JwtAuthProvider;
import com.sneezecast.security.common.enums.SecurityRole;
import com.sneezecast.storage.init.StorageBucketInitializer;
import feign.FeignException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.openfeign.FeignClientProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 내 동네 API 를 실제 필터 체인 · 메서드 보안 · JPA(H2)로 끝까지 본다. surveillance 조회 포트만 mock 이다 — Feign · 서킷 판정은
 * {@code DistrictClientAdapterTest} · {@code InternalClientSupportTest} 가 본다. 여기서는 그 설정(yml)이 실제 빈에 바인딩되는지를 본다.
 *
 * <p>외부 의존은 {@code AuthServiceApplicationTests} 와 같은 방식으로 바꾼다 — H2, Eureka 끔, Redis · MinIO · SMTP 는 mock.
 */
@SpringBootTest(properties = {
    "spring.profiles.active=dev",
    "eureka.client.enabled=false",
    "AUTH_SERVICE_PORT=0",
    "SERVICE_DISCOVERY_HOSTNAME=localhost",
    "SERVICE_DISCOVERY_PORT=8761",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "AUTH_DB_URL=jdbc:h2:mem:auth-region-it;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "AUTH_DB_USERNAME=sa",
    "AUTH_DB_PASSWORD=",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "JWT_ACCESS_KEY=sneezecast-auth-region-test-access-secret-key-0123456789-0123456789",
    "JWT_REFRESH_KEY=sneezecast-auth-region-test-refresh-secret-key-0123456789-0123456789",
    "REDIS_MASTER_NAME=mymaster",
    "REDIS_SENTINEL_NODES=localhost:26379",
    "REDIS_PASSWORD=",
    "MINIO_ENDPOINT=http://localhost:9000",
    "MINIO_PUBLIC_URL=http://localhost:9000",
    "MINIO_BUCKET=sneezecast-region-test",
    "MINIO_ACCESS_KEY=region-test-access-key",
    "MINIO_SECRET_KEY=region-test-secret-key",
    "MAIL_USERNAME=region-test",
    "MAIL_PASSWORD=region-test-password",
    // 카카오 앱 키는 기본값이 없는 필수 설정이라(#61, 비면 기동 실패) 전체 컨텍스트 테스트도 값을 넣는다.
    "KAKAO_CLIENT_ID=region-test-kakao-client-id",
    "KAKAO_CLIENT_SECRET=region-test-kakao-client-secret",
    "KAKAO_REDIRECT_URI=https://dev.sneezecast.com/login/kakao/callback"
})
@AutoConfigureMockMvc
class RegionApiIntegrationTest {

    private static final String PATH = "/api/v1/members/me/region";
    private static final long MEMBER_ID = 1843956734582784L;

    @MockitoBean(enforceOverride = true)
    private StringRedisTemplate stringRedisTemplate;

    @MockitoBean(enforceOverride = true)
    private StorageBucketInitializer storageBucketInitializer;

    @MockitoBean(enforceOverride = true)
    private JavaMailSender javaMailSender;

    @MockitoBean(enforceOverride = true)
    private DistrictQueryPort districtQueryPort;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtAuthProvider jwtAuthProvider;

    @Autowired
    private MemberRegionRepository memberRegionRepository;

    @Autowired
    private ApplicationContext context;

    @BeforeEach
    void notRevoked() {
        when(stringRedisTemplate.hasKey(anyString())).thenReturn(false);
    }

    @AfterEach
    void cleanUp() {
        memberRegionRepository.deleteAll();
    }

    @Test
    @DisplayName("토큰이 없으면 저장 · 조회 모두 401 SECURITY_001 봉투다 — @PreAuthorize 가 실제로 걸려 있고 surveillance 를 부르지 않는다")
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get(PATH))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("SECURITY_001"));
        mockMvc.perform(put(PATH).contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"11230510\"}"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("SECURITY_001"));

        verifyNoInteractions(districtQueryPort);
    }

    @Test
    @DisplayName("토큰으로 조회(없음 → null) → 저장 → 조회 → 다시 저장 흐름이 회원당 1행으로 이어진다")
    void saveAndGetFlow() throws Exception {
        when(districtQueryPort.findByCode("11230510")).thenReturn(Optional.of(new DistrictQueryResult("11230510", "역삼1동", "서울특별시 강남구", true)));
        when(districtQueryPort.findByCode("21120561")).thenReturn(Optional.of(new DistrictQueryResult("21120561", "녹산동", "부산광역시 강서구", true)));

        mockMvc.perform(get(PATH).header(HttpHeaders.AUTHORIZATION, bearer()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dataHeader.success").value(true))
            .andExpect(jsonPath("$.dataBody").value(nullValue()));

        mockMvc.perform(put(PATH).header(HttpHeaders.AUTHORIZATION, bearer()).contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"11230510\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dataBody.code").value("11230510"))
            .andExpect(jsonPath("$.dataBody.abolished").value(false));

        mockMvc.perform(get(PATH).header(HttpHeaders.AUTHORIZATION, bearer()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dataBody.code").value("11230510"))
            .andExpect(jsonPath("$.dataBody.name").value("역삼1동"))
            .andExpect(jsonPath("$.dataBody.sigungu").value("서울특별시 강남구"))
            .andExpect(jsonPath("$.dataBody.abolished").value(false));

        mockMvc.perform(put(PATH).header(HttpHeaders.AUTHORIZATION, bearer()).contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"21120561\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dataBody.name").value("녹산동"));

        assertThat(memberRegionRepository.findAll()).singleElement().satisfies(entity -> {
            assertThat(entity.getMemberId()).isEqualTo(MEMBER_ID);
            assertThat(entity.getDistrictCode()).isEqualTo("21120561");
        });
    }

    @Test
    @DisplayName("폐지된 코드는 400 REGION_002 이고 저장되지 않는다")
    void abolishedCodeIsNotSaved() throws Exception {
        when(districtQueryPort.findByCode("21120560")).thenReturn(Optional.of(new DistrictQueryResult("21120560", "녹산동", "부산광역시 강서구", false)));

        mockMvc.perform(put(PATH).header(HttpHeaders.AUTHORIZATION, bearer()).contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"21120560\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("REGION_002"));

        assertThat(memberRegionRepository.count()).isZero();
    }

    @Test
    @DisplayName("Feign 클라이언트 빈이 Eureka 없이도 만들어지고, 공통 timeout(1s · 3s)이 바인딩된다")
    void feignClientAndTimeoutsAreConfigured() {
        assertThat(context.getBeansOfType(DistrictClient.class)).hasSize(1);
        assertThat(context.getBean(InternalClientSupport.class)).isNotNull();

        FeignClientProperties.FeignClientConfiguration defaults = context.getBean(FeignClientProperties.class).getConfig().get("default");
        assertThat(defaults.getConnectTimeout()).isEqualTo(1000);
        assertThat(defaults.getReadTimeout()).isEqualTo(3000);
    }

    @Test
    @DisplayName("surveillance 서킷 설정이 yml 대로 바인딩된다 — 4xx(FeignClientException)는 무시하고 5xx 는 센다")
    void surveillanceCircuitIgnoresClientErrors() {
        CircuitBreakerConfig config = context.getBean(CircuitBreakerRegistry.class)
            .circuitBreaker(InternalClientSupport.SURVEILLANCE_SERVICE).getCircuitBreakerConfig();

        assertThat(config.getSlidingWindowSize()).isEqualTo(20);
        assertThat(config.getMinimumNumberOfCalls()).isEqualTo(10);
        assertThat(config.getFailureRateThreshold()).isEqualTo(50f);
        FeignException notFound = FeignExceptionFixtures.status(404, FeignExceptionFixtures.DISTRICT_NOT_FOUND_BODY);
        FeignException serverError = FeignExceptionFixtures.status(500, "{}");
        assertThat(config.getIgnoreExceptionPredicate().test(notFound)).isTrue();
        assertThat(config.getIgnoreExceptionPredicate().test(serverError)).isFalse();
        assertThat(config.getRecordExceptionPredicate().test(serverError)).isTrue();
    }

    private String bearer() {
        return "Bearer " + jwtAuthProvider.issueAccessToken(MEMBER_ID, SecurityRole.USER, Set.of(), null).value();
    }
}
