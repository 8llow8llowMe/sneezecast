package com.sneezecast.domainlayer.region;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sneezecast.domainlayer.region.adapter.out.persistence.entity.MemberInterestRegionEntity;
import com.sneezecast.domainlayer.region.adapter.out.persistence.repository.MemberInterestRegionRepository;
import com.sneezecast.domainlayer.region.adapter.out.persistence.repository.MemberRegionRepository;
import com.sneezecast.domainlayer.region.application.exception.RegionErrorCode;
import com.sneezecast.domainlayer.region.application.exception.RegionException;
import com.sneezecast.domainlayer.region.application.port.out.DistrictQueryPort;
import com.sneezecast.domainlayer.region.application.port.out.query.DistrictQueryResult;
import com.sneezecast.global.properties.RegionInterestProperties;
import com.sneezecast.security.auth.jwt.JwtAuthProvider;
import com.sneezecast.security.common.enums.SecurityRole;
import com.sneezecast.storage.init.StorageBucketInitializer;
import java.util.Optional;
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
 * 관심 동네 API 를 실제 필터 체인 · 메서드 보안 · JPA(H2) · yml 설정으로 끝까지 본다. surveillance 조회 포트만 mock 이다.
 *
 * <p>설정 · mock 구성은 {@code RegionApiIntegrationTest} 와 같다 — 같은 스프링 컨텍스트를 캐시에서 다시 쓰게 하려는 것이니 한쪽을 바꾸면 다른 쪽도
 * 같이 바꾼다. 두 테스트가 같은 H2 를 쓰므로 테스트마다 두 테이블을 비운다.
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
class InterestRegionApiIntegrationTest {

    private static final String PATH = "/api/v1/members/me/interest-regions";
    private static final long MEMBER_ID = 1843956734582784L;
    private static final String YEOKSAM = "11230510";
    private static final String NOKSAN = "21120561";
    private static final String SAMSEONG = "11230520";
    private static final String DAECHI = "11230530";
    private static final String MY_REGION = "11240660";

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
    private MemberInterestRegionRepository memberInterestRegionRepository;

    @Autowired
    private MemberRegionRepository memberRegionRepository;

    @Autowired
    private RegionInterestProperties regionInterestProperties;

    @BeforeEach
    void setUp() {
        when(stringRedisTemplate.hasKey(anyString())).thenReturn(false);
        district(YEOKSAM, "역삼1동", true);
        district(NOKSAN, "녹산동", true);
        district(SAMSEONG, "삼성1동", true);
        district(DAECHI, "대치1동", true);
        district(MY_REGION, "역삼2동", true);
    }

    @AfterEach
    void cleanUp() {
        memberInterestRegionRepository.deleteAll();
        memberRegionRepository.deleteAll();
    }

    @Test
    @DisplayName("상한은 application.yml 의 region.interest.max-count 기본값 3 이다")
    void maxCountIsBoundFromYaml() {
        assertThat(regionInterestProperties.maxCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("토큰이 없으면 목록 · 추가 · 삭제 모두 401 SECURITY_001 봉투다 — @PreAuthorize 가 실제로 걸려 있고 surveillance 를 부르지 않는다")
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get(PATH))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("SECURITY_001"));
        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"11230510\"}"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("SECURITY_001"));
        mockMvc.perform(delete(PATH + "/" + YEOKSAM))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("SECURITY_001"));

        verifyNoInteractions(districtQueryPort);
        assertThat(memberInterestRegionRepository.count()).isZero();
    }

    @Test
    @DisplayName("빈 목록 → 추가 → 추가 → 삭제 흐름이 바뀐 뒤의 목록을 고른 순서로 돌려준다 (report:write 없이)")
    void addListRemoveFlow() throws Exception {
        authed(get(PATH))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dataBody.contents.length()").value(0))
            .andExpect(jsonPath("$.dataBody.hasNext").value(false));

        add(YEOKSAM).andExpect(status().isOk()).andExpect(jsonPath("$.dataBody.contents[0].name").value("역삼1동"));
        add(NOKSAN)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dataBody.contents.length()").value(2))
            .andExpect(jsonPath("$.dataBody.contents[0].code").value(YEOKSAM))
            .andExpect(jsonPath("$.dataBody.contents[1].code").value(NOKSAN))
            .andExpect(jsonPath("$.dataBody.contents[1].sigungu").value("서울특별시 강남구"))
            .andExpect(jsonPath("$.dataBody.contents[1].abolished").value(false));

        authed(delete(PATH + "/" + YEOKSAM))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dataBody.contents.length()").value(1))
            .andExpect(jsonPath("$.dataBody.contents[0].code").value(NOKSAN));
        // 이미 지운 동네를 다시 지워도 성공이다.
        authed(delete(PATH + "/" + YEOKSAM))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dataBody.contents.length()").value(1));

        assertThat(memberInterestRegionRepository.findAll()).singleElement().satisfies(entity -> {
            assertThat(entity.getMemberId()).isEqualTo(MEMBER_ID);
            assertThat(entity.getDistrictCode()).isEqualTo(NOKSAN);
        });
    }

    @Test
    @DisplayName("검증 오류는 본문 REGION_101 · 102, 경로 REGION_102 이고 surveillance 를 부르지 않는다")
    void validationErrors() throws Exception {
        authed(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("REGION_101"));
        add("1123051")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("REGION_102"));
        authed(delete(PATH + "/1123051a"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("REGION_102"))
            .andExpect(jsonPath("$.dataHeader.fieldErrors[0].field").value("code"));

        verifyNoInteractions(districtQueryPort);
    }

    @Test
    @DisplayName("내 동네와 같으면 REGION_007, 이미 고르면 REGION_006, 3곳이 찼으면 REGION_005 — 모두 409 이고 봉투에 목록이 없다")
    void conflicts() throws Exception {
        authed(put("/api/v1/members/me/region").contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"" + MY_REGION + "\"}"))
            .andExpect(status().isOk());

        assertConflict(add(MY_REGION), RegionErrorCode.INTEREST_REGION_SAME_AS_MY_REGION);
        add(YEOKSAM).andExpect(status().isOk());
        assertConflict(add(YEOKSAM), RegionErrorCode.INTEREST_REGION_ALREADY_SELECTED);
        add(NOKSAN).andExpect(status().isOk());
        add(SAMSEONG).andExpect(status().isOk());
        assertConflict(add(DAECHI), RegionErrorCode.INTEREST_REGION_LIMIT_EXCEEDED);

        assertThat(memberInterestRegionRepository.findAll()).extracting(MemberInterestRegionEntity::getDistrictCode)
            .containsExactlyInAnyOrder(YEOKSAM, NOKSAN, SAMSEONG);
    }

    @Test
    @DisplayName("내 동네를 관심 동네 중 하나로 바꿔도 관심 동네는 그대로 남는다")
    void changingMyRegionKeepsInterestRegions() throws Exception {
        add(YEOKSAM).andExpect(status().isOk());

        authed(put("/api/v1/members/me/region").contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"" + YEOKSAM + "\"}"))
            .andExpect(status().isOk());

        authed(get(PATH))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dataBody.contents[0].code").value(YEOKSAM));
    }

    @Test
    @DisplayName("폐지된 코드는 400 REGION_002, 없는 코드는 400 REGION_001 이고 더하지 않는다")
    void unselectableDistricts() throws Exception {
        district("21120560", "녹산동", false);
        when(districtQueryPort.findByCode("99999999")).thenReturn(Optional.empty());

        add("21120560").andExpect(status().isBadRequest()).andExpect(jsonPath("$.dataHeader.resultCode").value("REGION_002"));
        add("99999999").andExpect(status().isBadRequest()).andExpect(jsonPath("$.dataHeader.resultCode").value("REGION_001"));

        assertThat(memberInterestRegionRepository.count()).isZero();
    }

    @Test
    @DisplayName("고른 뒤 폐지된 동네는 abolished=true 로, surveillance 에 없는 동네는 이름 null 로 목록에 남는다")
    void abolishedAndMissingRegionsAreShown() throws Exception {
        add(YEOKSAM).andExpect(status().isOk());
        add(NOKSAN).andExpect(status().isOk());
        district(YEOKSAM, "역삼1동", false);
        when(districtQueryPort.findByCode(NOKSAN)).thenReturn(Optional.empty());

        authed(get(PATH))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dataBody.contents[0].code").value(YEOKSAM))
            .andExpect(jsonPath("$.dataBody.contents[0].name").value("역삼1동"))
            .andExpect(jsonPath("$.dataBody.contents[0].abolished").value(true))
            .andExpect(jsonPath("$.dataBody.contents[1].code").value(NOKSAN))
            .andExpect(jsonPath("$.dataBody.contents[1].name").value(nullValue()))
            .andExpect(jsonPath("$.dataBody.contents[1].sigungu").value(nullValue()))
            .andExpect(jsonPath("$.dataBody.contents[1].abolished").value(true));
        assertThat(memberInterestRegionRepository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("surveillance 장애면 목록 · 추가 모두 503 REGION_004 이고, 추가는 일어나지 않는다")
    void surveillanceUnavailable() throws Exception {
        add(YEOKSAM).andExpect(status().isOk());
        when(districtQueryPort.findByCode(anyString())).thenThrow(new RegionException(RegionErrorCode.INTERNAL_SERVICE_UNAVAILABLE));

        authed(get(PATH))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("REGION_004"));
        add(NOKSAN)
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.dataHeader.resultCode").value("REGION_004"));

        assertThat(memberInterestRegionRepository.findAll()).extracting(MemberInterestRegionEntity::getDistrictCode).containsExactly(YEOKSAM);
    }

    private void district(String code, String name, boolean active) {
        when(districtQueryPort.findByCode(code)).thenReturn(Optional.of(new DistrictQueryResult(code, name, "서울특별시 강남구", active)));
    }

    private ResultActions add(String code) throws Exception {
        return authed(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"" + code + "\"}"));
    }

    private ResultActions authed(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION, bearer()));
    }

    private static void assertConflict(ResultActions result, RegionErrorCode errorCode) throws Exception {
        result.andExpect(status().isConflict())
            .andExpect(jsonPath("$.dataHeader.resultCode").value(errorCode.getCode()))
            .andExpect(jsonPath("$.dataBody").value(nullValue()));
    }

    /** scope 없이(건강정보 동의 전) 발급한 토큰이다 — 관심 동네는 report:write 를 요구하지 않는다. */
    private String bearer() {
        return "Bearer " + jwtAuthProvider.issueAccessToken(MEMBER_ID, SecurityRole.USER, Set.of(), null).value();
    }
}
