package com.sneezecast.domainlayer.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sneezecast.domainlayer.member.adapter.out.persistence.entity.MemberNotificationSettingEntity;
import com.sneezecast.domainlayer.member.adapter.out.persistence.repository.MemberNotificationSettingRepository;
import com.sneezecast.domainlayer.member.adapter.out.persistence.repository.MemberRepository;
import com.sneezecast.domainlayer.member.application.port.out.MemberRepositoryPort;
import com.sneezecast.domainlayer.member.domain.enums.MemberStatus;
import com.sneezecast.domainlayer.member.domain.model.Member;
import com.sneezecast.domainlayer.region.application.port.out.DistrictQueryPort;
import com.sneezecast.security.auth.jwt.JwtAuthProvider;
import com.sneezecast.security.common.enums.SecurityRole;
import com.sneezecast.storage.init.StorageBucketInitializer;
import java.time.LocalDateTime;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 알림 설정 API 를 실제 필터 체인 · 메서드 보안 · Boot 의 ObjectMapper · JPA(H2)로 끝까지 본다. 엄격한 본문 검증이 전역 Jackson 설정과 상관없이
 * 이 DTO 에서 동작하는지(문자열 "true" · 모르는 항목 거절)를 실제 컨텍스트에서 확인한다.
 *
 * <p>설정 · mock 구성은 {@code RegionApiIntegrationTest} · {@code InterestRegionApiIntegrationTest} 와 같다 — 같은 스프링 컨텍스트를 캐시에서 다시
 * 쓰게 하려는 것이니 한쪽을 바꾸면 다른 쪽도 같이 바꾼다(행정동 조회 포트 mock 도 그래서 둔다). 같은 H2 를 쓰므로 테스트마다 쓴 테이블을 비운다.
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
class MemberNotificationSettingApiIntegrationTest {

    private static final String PATH = "/api/v1/members/me/notification-settings";
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
    private MemberRepositoryPort memberRepositoryPort;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private MemberNotificationSettingRepository memberNotificationSettingRepository;

    @BeforeEach
    void setUp() {
        when(stringRedisTemplate.hasKey(anyString())).thenReturn(false);
    }

    @AfterEach
    void cleanUp() {
        memberNotificationSettingRepository.deleteAll();
        memberRepository.deleteAll();
    }

    @Test
    @DisplayName("토큰이 없으면 조회 · 변경 모두 401 SECURITY_001 봉투다 — @PreAuthorize 가 실제로 걸려 있고 행을 만들지 않는다")
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get(PATH))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("SECURITY_001"));
        mockMvc.perform(patch(PATH).contentType(MediaType.APPLICATION_JSON).content("{\"weeklyReport\":true}"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("SECURITY_001"));

        assertThat(memberNotificationSettingRepository.count()).isZero();
    }

    @Test
    @DisplayName("처음엔 모두 false(행 없음) → 한 항목씩 바꾸면 보낸 항목만 바뀐 설정 전부를 돌려준다 (report:write 없이)")
    void getAndPatchFlow() throws Exception {
        saveMember(MemberStatus.ACTIVE);

        authed(get(PATH))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dataBody.weeklyReport").value(false))
            .andExpect(jsonPath("$.dataBody.regionNotice").value(false));
        assertThat(memberNotificationSettingRepository.count()).isZero();

        patchJson("{\"weeklyReport\":true}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dataBody.weeklyReport").value(true))
            .andExpect(jsonPath("$.dataBody.regionNotice").value(false));
        patchJson("{\"regionNotice\":true}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dataBody.weeklyReport").value(true))
            .andExpect(jsonPath("$.dataBody.regionNotice").value(true));
        patchJson("{\"weeklyReport\":false,\"regionNotice\":false}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dataBody.weeklyReport").value(false))
            .andExpect(jsonPath("$.dataBody.regionNotice").value(false));

        assertThat(memberNotificationSettingRepository.findAll()).singleElement()
            .extracting(MemberNotificationSettingEntity::getMemberId).isEqualTo(MEMBER_ID);
    }

    @Test
    @DisplayName("Boot 의 ObjectMapper 로도 빈 본문 MEMBER_111 · 문자열 \"true\" MEMBER_112 · 숫자 MEMBER_113 · 모르는 항목 MEMBER_114 이고 아무것도 바꾸지 않는다")
    void strictBodyWithBootObjectMapper() throws Exception {
        saveMember(MemberStatus.ACTIVE);

        assertValidationError(patchJson("{}"), "MEMBER_111", "request");
        assertValidationError(patchJson("{\"weeklyReport\":\"true\"}"), "MEMBER_112", "weeklyReport");
        assertValidationError(patchJson("{\"regionNotice\":1}"), "MEMBER_113", "regionNotice");
        assertValidationError(patchJson("{\"weeklyReport\":null}"), "MEMBER_112", "weeklyReport");
        assertValidationError(patchJson("{\"weeklyReport\":true,\"pushEnabled\":true}"), "MEMBER_114", "unknownItems");

        assertThat(memberNotificationSettingRepository.count()).isZero();
    }

    @Test
    @DisplayName("회원 행이 없으면 MEMBER_004(404), 탈퇴 회원이면 MEMBER_002(403)이고 설정 행을 만들지 않는다")
    void inactiveMember() throws Exception {
        patchJson("{\"weeklyReport\":true}")
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("MEMBER_004"));

        saveMember(MemberStatus.WITHDRAWN);
        authed(get(PATH))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("MEMBER_002"));
        patchJson("{\"weeklyReport\":true}")
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("MEMBER_002"));

        assertThat(memberNotificationSettingRepository.count()).isZero();
    }

    private void saveMember(MemberStatus status) {
        memberRepositoryPort.save(Member.builder().id(MEMBER_ID).email("notify@example.com").password("$2a$10$hash").nickname("알림이웃")
            .role(SecurityRole.USER).status(status).withdrawnAt(status == MemberStatus.WITHDRAWN ? LocalDateTime.now() : null).build());
    }

    private static void assertValidationError(ResultActions result, String code, String field) throws Exception {
        result.andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.dataHeader.resultCode").value(code))
            .andExpect(jsonPath("$.dataHeader.fieldErrors.length()").value(1))
            .andExpect(jsonPath("$.dataHeader.fieldErrors[0].field").value(field));
    }

    private ResultActions patchJson(String body) throws Exception {
        return authed(patch(PATH).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions authed(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION, bearer()));
    }

    /** scope 없이(건강정보 동의 전) 발급한 토큰이다 — 알림 설정은 report:write 를 요구하지 않는다. */
    private String bearer() {
        return "Bearer " + jwtAuthProvider.issueAccessToken(MEMBER_ID, SecurityRole.USER, Set.of(), null).value();
    }
}
