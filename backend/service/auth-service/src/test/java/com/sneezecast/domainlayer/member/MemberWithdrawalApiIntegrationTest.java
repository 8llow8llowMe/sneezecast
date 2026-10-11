package com.sneezecast.domainlayer.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sneezecast.domainlayer.auth.application.command.AuthOAuthSignupCommand;
import com.sneezecast.domainlayer.auth.application.model.OAuthSignupTicket;
import com.sneezecast.domainlayer.auth.application.service.processor.OAuthMemberProcessor;
import com.sneezecast.domainlayer.member.adapter.out.persistence.entity.MemberEntity;
import com.sneezecast.domainlayer.member.adapter.out.persistence.entity.ReportPurgeRequestEntity;
import com.sneezecast.domainlayer.member.adapter.out.persistence.repository.MemberConsentRepository;
import com.sneezecast.domainlayer.member.adapter.out.persistence.repository.MemberNotificationSettingRepository;
import com.sneezecast.domainlayer.member.adapter.out.persistence.repository.MemberRepository;
import com.sneezecast.domainlayer.member.adapter.out.persistence.repository.ReportPurgeRequestRepository;
import com.sneezecast.domainlayer.member.application.port.out.MemberNotificationSettingRepositoryPort;
import com.sneezecast.domainlayer.member.application.port.out.MemberRepositoryPort;
import com.sneezecast.domainlayer.member.application.service.processor.MemberConsentProcessor;
import com.sneezecast.domainlayer.member.domain.enums.MemberStatus;
import com.sneezecast.domainlayer.member.domain.enums.OAuthProvider;
import com.sneezecast.domainlayer.member.domain.enums.PurgeReason;
import com.sneezecast.domainlayer.member.domain.model.Member;
import com.sneezecast.domainlayer.member.domain.model.MemberNotificationSetting;
import com.sneezecast.domainlayer.region.adapter.out.persistence.repository.MemberInterestRegionRepository;
import com.sneezecast.domainlayer.region.application.port.out.DistrictQueryPort;
import com.sneezecast.domainlayer.region.application.port.out.MemberInterestRegionRepositoryPort;
import com.sneezecast.domainlayer.region.domain.model.MemberInterestRegion;
import com.sneezecast.security.auth.jwt.JwtAuthProvider;
import com.sneezecast.security.common.enums.SecurityRole;
import com.sneezecast.storage.init.StorageBucketInitializer;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 탈퇴 API 를 실제 필터 체인 · 메서드 보안 · JPA(H2) · 트랜잭션으로 끝까지 보고, <b>탈퇴 즉시 같은 이메일로 다시 가입할 수 있다</b>(entity-design §1-1)는 것과
 * 탈퇴한 계정이 로그인에서 <b>가입되지 않은 계정과 같은 응답</b>이라는 것을 고정한다.
 *
 * <p>설정 · mock 구성은 {@code MemberNotificationSettingApiIntegrationTest} · {@code RegionApiIntegrationTest} · {@code InterestRegionApiIntegrationTest} 와
 * 같다 — 같은 스프링 컨텍스트를 캐시에서 다시 쓰게 하려는 것이니 한쪽을 바꾸면 다른 쪽도 같이 바꾼다. Redis({@link StringRedisTemplate})는 mock 이라 세션
 * 폐기(Lua)는 저장소 장애로 실패시키고, 탈퇴가 그래도 성공하는지 본다.
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
    "KAKAO_REDIRECT_URI=https://dev.sneezecast.com/login/kakao/callback",
    // 탈퇴 이메일 다이제스트 pepper 도 기본값이 없는 필수 설정이다(#154, 비거나 32자 미만이면 기동 실패).
    "WITHDRAWN_EMAIL_PEPPER=sneezecast-test-withdrawn-email-pepper-0123456789"
})
@AutoConfigureMockMvc
class MemberWithdrawalApiIntegrationTest {

    private static final String PATH = "/api/v1/members/me/withdraw";
    private static final long MEMBER_ID = 1843956734582784L;
    private static final String EMAIL = "withdraw@example.com";
    private static final String PASSWORD = "P@ssw0rd!";

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
    private PasswordEncoder passwordEncoder;

    @Autowired
    private MemberRepositoryPort memberRepositoryPort;

    @Autowired
    private MemberConsentProcessor memberConsentProcessor;

    @Autowired
    private MemberInterestRegionRepositoryPort memberInterestRegionRepositoryPort;

    @Autowired
    private MemberNotificationSettingRepositoryPort memberNotificationSettingRepositoryPort;

    @Autowired
    private OAuthMemberProcessor oAuthMemberProcessor;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private MemberConsentRepository memberConsentRepository;

    @Autowired
    private ReportPurgeRequestRepository reportPurgeRequestRepository;

    @Autowired
    private MemberInterestRegionRepository memberInterestRegionRepository;

    @Autowired
    private MemberNotificationSettingRepository memberNotificationSettingRepository;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        // 블랙리스트에 없는 토큰이다.
        when(stringRedisTemplate.hasKey(anyString())).thenReturn(false);
        // 로그인 시도 카운터는 늘 첫 시도다.
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(values.increment(anyString())).thenReturn(1L);
        when(stringRedisTemplate.opsForValue()).thenReturn(values);
        // 세션 저장소(Lua) 장애 — 탈퇴 커밋 뒤의 모든 기기 로그아웃이 실패한다.
        when(stringRedisTemplate.execute(any(RedisScript.class), anyList(), any(), any())).thenThrow(new RedisConnectionFailureException("down"));
    }

    @AfterEach
    void cleanUp() {
        memberNotificationSettingRepository.deleteAll();
        memberInterestRegionRepository.deleteAll();
        reportPurgeRequestRepository.deleteAll();
        memberConsentRepository.deleteAll();
        memberRepository.deleteAll();
    }

    @Test
    @DisplayName("탈퇴하면 200 · refresh 쿠키 삭제이고(세션 폐기가 실패해도) 회원 행 · 파기 요청 · 관심 동네 · 알림 설정이 바뀐다 — 남은 access 는 MEMBER_002 다")
    void withdrawFlow() throws Exception {
        givenEmailMember(MEMBER_ID);
        memberInterestRegionRepositoryPort.insert(MemberInterestRegion.builder().id(11L).memberId(MEMBER_ID).districtCode("11110515").slot(1).build());
        memberNotificationSettingRepositoryPort.insert(MemberNotificationSetting.builder().id(12L).memberId(MEMBER_ID).weeklyReport(true).build());
        String bearer = bearer(MEMBER_ID);

        mockMvc.perform(post(PATH).header(HttpHeaders.AUTHORIZATION, bearer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dataHeader.success").value(true))
            .andExpect(header().string(HttpHeaders.SET_COOKIE, startsWith("refreshToken=;")))
            .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")));

        MemberEntity withdrawn = memberRepository.findById(MEMBER_ID).orElseThrow();
        assertThat(withdrawn.getStatus()).isEqualTo(MemberStatus.WITHDRAWN);
        assertThat(withdrawn.getEmail()).hasSize(64).doesNotContain("@");
        assertThat(withdrawn.getPassword()).isNull();
        assertThat(reportPurgeRequestRepository.findAll()).extracting(ReportPurgeRequestEntity::getReason).containsExactly(PurgeReason.WITHDRAWAL);
        assertThat(memberInterestRegionRepository.count()).isZero();
        assertThat(memberNotificationSettingRepository.count()).isZero();
        assertThat(memberConsentRepository.findAllByMemberId(MEMBER_ID)).as("동의 이력은 회원 파기 때 지운다").isNotEmpty();

        mockMvc.perform(post(PATH).header(HttpHeaders.AUTHORIZATION, bearer))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("MEMBER_002"));
        mockMvc.perform(get("/api/v1/members/me").header(HttpHeaders.AUTHORIZATION, bearer))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("MEMBER_002"));
        assertThat(reportPurgeRequestRepository.count()).as("파기 요청은 하나뿐이다").isEqualTo(1);
    }

    @Test
    @DisplayName("탈퇴 후 원래 이메일 · 비밀번호로 로그인하면 가입되지 않은 이메일과 같은 401 AUTH_011 이다 — MEMBER_002 로 탈퇴 사실을 드러내지 않는다")
    void loginAfterWithdrawalLooksUnregistered() throws Exception {
        givenEmailMember(MEMBER_ID);
        mockMvc.perform(post(PATH).header(HttpHeaders.AUTHORIZATION, bearer(MEMBER_ID))).andExpect(status().isOk());

        login(EMAIL, PASSWORD)
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("AUTH_011"));
        login("never-registered@example.com", PASSWORD)
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("AUTH_011"));
        assertThat(memberRepositoryPort.findByEmail(EMAIL)).as("카카오 로그인도 이메일로 찾으므로 가입되지 않은 계정과 같다(SIGNUP_REQUIRED)").isEmpty();
    }

    @Test
    @DisplayName("탈퇴 후 같은 이메일로 이메일 가입이 된다 — 새 회원 행이 생기고 탈퇴 행은 남는다")
    void emailSignupAfterWithdrawal() throws Exception {
        givenEmailMember(MEMBER_ID);
        mockMvc.perform(post(PATH).header(HttpHeaders.AUTHORIZATION, bearer(MEMBER_ID))).andExpect(status().isOk());
        // 이메일 인증을 마친 상태다.
        when(stringRedisTemplate.hasKey(argThat((String key) -> key != null && key.contains(":emailVerified:")))).thenReturn(true);

        mockMvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON).content("""
                {"email":"%s","password":"%s","nickname":"다시온이웃","termsAgreed":true,"privacyAgreed":true,"ageOver19Confirmed":true}
                """.formatted(EMAIL, PASSWORD)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dataHeader.success").value(true));

        Member rejoined = memberRepositoryPort.findByEmail(EMAIL).orElseThrow();
        assertThat(rejoined.id()).isNotEqualTo(MEMBER_ID);
        assertThat(rejoined.status()).isEqualTo(MemberStatus.ACTIVE);
        assertThat(passwordEncoder.matches(PASSWORD, rejoined.password())).isTrue();
        assertThat(memberRepositoryPort.findById(MEMBER_ID).orElseThrow().status()).as("탈퇴 행은 보고 파기까지 남는다").isEqualTo(MemberStatus.WITHDRAWN);
    }

    @Test
    @DisplayName("탈퇴 후 같은 이메일로 카카오 가입이 되고, 그 계정도 다시 탈퇴할 수 있다 — 탈퇴 행 둘이 uk_member_email 에 막히지 않는다")
    void kakaoSignupAndSecondWithdrawalAfterWithdrawal() throws Exception {
        givenEmailMember(MEMBER_ID);
        mockMvc.perform(post(PATH).header(HttpHeaders.AUTHORIZATION, bearer(MEMBER_ID))).andExpect(status().isOk());

        Member kakaoMember = oAuthMemberProcessor.signup(new OAuthSignupTicket(OAuthProvider.KAKAO, EMAIL, "카카오이웃"),
            AuthOAuthSignupCommand.builder().signupTicket("ticket").termsAgreed(true).privacyAgreed(true).ageOver19Confirmed(true).build());

        assertThat(kakaoMember.id()).isNotEqualTo(MEMBER_ID);
        assertThat(memberRepositoryPort.findByEmail(EMAIL)).get().extracting(Member::provider).isEqualTo(OAuthProvider.KAKAO);

        mockMvc.perform(post(PATH).header(HttpHeaders.AUTHORIZATION, bearer(kakaoMember.id()))).andExpect(status().isOk());
        assertThat(memberRepository.findAll()).extracting(MemberEntity::getStatus).containsOnly(MemberStatus.WITHDRAWN);
        assertThat(memberRepository.findAll()).extracting(MemberEntity::getEmail).doesNotHaveDuplicates();
        assertThat(reportPurgeRequestRepository.count()).isEqualTo(2);
    }

    private void givenEmailMember(long memberId) {
        memberRepositoryPort.save(Member.builder().id(memberId).email(EMAIL).password(passwordEncoder.encode(PASSWORD)).nickname("탈퇴할이웃")
            .role(SecurityRole.USER).status(MemberStatus.ACTIVE).build());
        memberConsentProcessor.recordSignupConsents(memberId, true);
    }

    private ResultActions login(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)));
    }

    private String bearer(long memberId) {
        return "Bearer " + jwtAuthProvider.issueAccessToken(memberId, SecurityRole.USER, Set.of(), null).value();
    }
}
