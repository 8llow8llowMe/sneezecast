package com.sneezecast.domainlayer.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sneezecast.domainlayer.member.adapter.out.persistence.entity.MemberEntity;
import com.sneezecast.domainlayer.member.adapter.out.persistence.repository.MemberConsentRepository;
import com.sneezecast.domainlayer.member.adapter.out.persistence.repository.MemberRepository;
import com.sneezecast.domainlayer.member.application.port.out.MemberRepositoryPort;
import com.sneezecast.domainlayer.member.application.service.processor.MemberConsentProcessor;
import com.sneezecast.domainlayer.member.domain.enums.MemberStatus;
import com.sneezecast.domainlayer.member.domain.model.Member;
import com.sneezecast.domainlayer.region.application.port.out.DistrictQueryPort;
import com.sneezecast.security.auth.jwt.JwtAuthProvider;
import com.sneezecast.security.common.enums.SecurityRole;
import com.sneezecast.storage.init.StorageBucketInitializer;
import com.sneezecast.storage.properties.StorageProperties;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.web.servlet.MultipartProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 프로필 이미지 API 를 실제 필터 체인 · 메서드 보안 · multipart 해석 · advice · JPA(H2)로 본다 (#112). 오브젝트 스토리지는 실제 클라이언트지만, 여기서 보는
 * 경로(빈 파일 · 크기 초과 · 형식 위장 · 인증 · 이미지 없는 삭제 · URL 조립)는 원격 I/O 전에 끝난다 — 업로드 성공 · 이전 이미지 삭제는 Facade 단위 테스트가 본다.
 *
 * <p>설정 · mock 구성은 {@code MemberWithdrawalApiIntegrationTest} 와 같다 — 같은 스프링 컨텍스트를 캐시에서 다시 쓰게 하려는 것이니 한쪽을 바꾸면
 * 다른 쪽도 같이 바꾼다.
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
    "KAKAO_CLIENT_ID=region-test-kakao-client-id",
    "KAKAO_CLIENT_SECRET=region-test-kakao-client-secret",
    "KAKAO_REDIRECT_URI=https://dev.sneezecast.com/login/kakao/callback",
    "WITHDRAWN_EMAIL_PEPPER=sneezecast-test-withdrawn-email-pepper-0123456789"
})
@AutoConfigureMockMvc
class MemberProfileImageApiIntegrationTest {

    private static final String PATH = "/api/v1/members/me/profile-image";
    private static final long MEMBER_ID = 1843956734582784L;
    private static final String KAKAO_URL = "https://k.kakaocdn.net/dn/profile.jpg";

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
    private MemberConsentProcessor memberConsentProcessor;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private MemberConsentRepository memberConsentRepository;

    @Autowired
    private MultipartProperties multipartProperties;

    @Autowired
    private StorageProperties storageProperties;

    @BeforeEach
    void setUp() {
        // 블랙리스트에 없는 토큰이다.
        when(stringRedisTemplate.hasKey(anyString())).thenReturn(false);
    }

    @AfterEach
    void cleanUp() {
        memberConsentRepository.deleteAll();
        memberRepository.deleteAll();
    }

    @Test
    @DisplayName("토큰 없이 업로드 · 삭제하면 401 SECURITY_001 이다")
    void requiresAuthentication() throws Exception {
        mockMvc.perform(multipart(PATH).file(png()))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("SECURITY_001"));
        mockMvc.perform(delete(PATH))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("SECURITY_001"));
    }

    @Test
    @DisplayName("빈 파일 · 파트 없음은 400 STORAGE_001, 확장자 · Content-Type 만 이미지인 파일은 400 STORAGE_003 이고 회원 행은 그대로다")
    void rejectsEmptyAndDisguisedFiles() throws Exception {
        givenMember(KAKAO_URL, null);

        mockMvc.perform(multipart(PATH).file(new MockMultipartFile("imageFile", "me.png", "image/png", new byte[0])).header(HttpHeaders.AUTHORIZATION, bearer()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("STORAGE_001"));
        mockMvc.perform(multipart(PATH).header(HttpHeaders.AUTHORIZATION, bearer()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("STORAGE_001"));
        byte[] text = "not an image, just text".getBytes(StandardCharsets.UTF_8);
        mockMvc.perform(multipart(PATH).file(new MockMultipartFile("imageFile", "me.png", "image/png", text)).header(HttpHeaders.AUTHORIZATION, bearer()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.dataHeader.success").value(false))
            .andExpect(jsonPath("$.dataHeader.resultCode").value("STORAGE_003"));

        MemberEntity member = memberRepository.findById(MEMBER_ID).orElseThrow();
        assertThat(member.getProfileImageUrl()).isEqualTo(KAKAO_URL);
        assertThat(member.getProfileImageKey()).isNull();
    }

    @Test
    @DisplayName("스토리지 상한(5MiB)을 1바이트 넘으면 봉투 있는 400 STORAGE_002 다 — 서블릿 파일 상한이 스토리지 상한보다 커서 컨트롤러까지 온다(봉투 없는 413 은 요청 상한부터)")
    void oversizedFileIsStorage002() throws Exception {
        assertThat(multipartProperties.getMaxFileSize().toBytes()).as("서블릿 파일 상한 > 스토리지 상한 — 같으면 1바이트 초과가 봉투 없는 413 이 된다")
            .isGreaterThan(storageProperties.normalizedMaxFileBytes())
            .isEqualTo(multipartProperties.getMaxRequestSize().toBytes());
        givenMember(null, null);
        byte[] oversized = new byte[(int) storageProperties.normalizedMaxFileBytes() + 1];

        mockMvc.perform(multipart(PATH).file(new MockMultipartFile("imageFile", "big.png", "image/png", oversized)).header(HttpHeaders.AUTHORIZATION, bearer()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("STORAGE_002"));
    }

    @Test
    @DisplayName("내 정보의 profileImageUrl — 업로드 key 의 공개 URL({publicUrl}/{bucket}/{key})이 소셜 URL 보다 앞서고, 소셜 URL 만 있으면 그것이다")
    void myInfoShowsProfileImageUrl() throws Exception {
        String key = "members/profiles/" + MEMBER_ID + "/2026/10/3f2a9c11-0e4b-4a1f-9c3d-0b8e2f7a5d61.png";
        givenMember(KAKAO_URL, key);
        mockMvc.perform(get("/api/v1/members/me").header(HttpHeaders.AUTHORIZATION, bearer()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dataBody.profileImageUrl").value("http://localhost:9000/sneezecast-region-test/" + key));

        memberRepository.deleteAll();
        givenMember(KAKAO_URL, null);
        mockMvc.perform(get("/api/v1/members/me").header(HttpHeaders.AUTHORIZATION, bearer()))
            .andExpect(jsonPath("$.dataBody.profileImageUrl").value(KAKAO_URL));
    }

    @Test
    @DisplayName("삭제하면 200 · dataBody null 이고 소셜 URL 도 비워져 내 정보의 profileImageUrl 이 null 이다 — 다시 지워도 200(멱등)")
    void removeClearsProfileImageIdempotently() throws Exception {
        givenMember(KAKAO_URL, null);

        mockMvc.perform(delete(PATH).header(HttpHeaders.AUTHORIZATION, bearer()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dataHeader.success").value(true))
            .andExpect(jsonPath("$.dataBody").value(nullValue()));
        mockMvc.perform(delete(PATH).header(HttpHeaders.AUTHORIZATION, bearer()))
            .andExpect(status().isOk());

        assertThat(memberRepository.findById(MEMBER_ID).orElseThrow().getProfileImageUrl()).isNull();
        mockMvc.perform(get("/api/v1/members/me").header(HttpHeaders.AUTHORIZATION, bearer()))
            .andExpect(jsonPath("$.dataBody.profileImageUrl").value(nullValue()));
    }

    private void givenMember(String profileImageUrl, String profileImageKey) {
        memberRepositoryPort.save(Member.builder().id(MEMBER_ID).email("profile@example.com").nickname("사진있는이웃").profileImageUrl(profileImageUrl)
            .profileImageKey(profileImageKey).role(SecurityRole.USER).status(MemberStatus.ACTIVE).build());
        memberConsentProcessor.recordSignupConsents(MEMBER_ID, true);
    }

    private static MockMultipartFile png() {
        return new MockMultipartFile("imageFile", "me.png", "image/png", new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});
    }

    private String bearer() {
        return "Bearer " + jwtAuthProvider.issueAccessToken(MEMBER_ID, SecurityRole.USER, Set.of(), null).value();
    }
}
