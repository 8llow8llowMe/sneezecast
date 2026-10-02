package com.sneezecast.domainlayer.region.adapter.out.client;

import static com.sneezecast.global.client.FeignExceptionFixtures.DISTRICT_NOT_FOUND_BODY;
import static com.sneezecast.global.client.FeignExceptionFixtures.SPRING_DEFAULT_NOT_FOUND_BODY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sneezecast.common.dto.Response;
import com.sneezecast.domainlayer.region.adapter.out.client.feign.DistrictClient;
import com.sneezecast.domainlayer.region.adapter.out.client.feign.dto.DistrictClientResponse;
import com.sneezecast.domainlayer.region.application.exception.RegionErrorCode;
import com.sneezecast.domainlayer.region.application.exception.RegionException;
import com.sneezecast.domainlayer.region.application.port.out.query.DistrictQueryResult;
import com.sneezecast.global.client.FeignExceptionFixtures;
import com.sneezecast.global.client.InternalClientSupport;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * surveillance 응답 → 포트 계약(있음 / 없음 / 503) 변환을 본다. Feign 인터페이스는 mock 이고, 서킷 · 예외 판정은 실제 헬퍼와 레지스트리를 쓴다.
 */
class DistrictClientAdapterTest {

    private static final String CODE = "11230510";

    private DistrictClient districtClient;
    private CircuitBreakerRegistry registry;
    private DistrictClientAdapter adapter;

    @BeforeEach
    void setUp() {
        districtClient = mock(DistrictClient.class);
        registry = FeignExceptionFixtures.smallRegistry();
        adapter = new DistrictClientAdapter(districtClient, new InternalClientSupport(registry, new ObjectMapper()));
    }

    @Test
    @DisplayName("200 이면 행정동을 돌려준다 — 폐지 여부(active)를 그대로 옮긴다")
    void returnsDistrict() {
        when(districtClient.getDistrict(CODE)).thenReturn(Response.success(new DistrictClientResponse(CODE, "역삼1동", "서울특별시 강남구", false)));

        assertThat(adapter.findByCode(CODE)).contains(new DistrictQueryResult(CODE, "역삼1동", "서울특별시 강남구", false));
    }

    @Test
    @DisplayName("404 + DISTRICT_001 이면 없는 코드라 empty 다 — 장애가 아니므로 서킷에 세지 않는다")
    void notFoundIsEmpty() {
        when(districtClient.getDistrict(CODE)).thenThrow(FeignExceptionFixtures.status(404, DISTRICT_NOT_FOUND_BODY));

        assertThat(adapter.findByCode(CODE)).isEmpty();
        assertThat(registry.circuitBreaker(InternalClientSupport.SURVEILLANCE_SERVICE).getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @Test
    @DisplayName("봉투 없는 404(상대가 그 경로를 모른다 — 옛 버전)는 '없는 코드' 가 아니라 503 이다 — 멀쩡한 동네를 다시 고르게 만들지 않는다")
    void routeNotFoundIsUnavailable() {
        when(districtClient.getDistrict(CODE)).thenThrow(FeignExceptionFixtures.status(404, SPRING_DEFAULT_NOT_FOUND_BODY));

        assertUnavailable();
    }

    @Test
    @DisplayName("400(형식 규칙이 서로 어긋남)도 계약 밖이라 503 이다")
    void badRequestIsUnavailable() {
        when(districtClient.getDistrict(CODE)).thenThrow(FeignExceptionFixtures.status(400,
            "{\"dataHeader\":{\"success\":false,\"resultCode\":\"DISTRICT_103\",\"resultMessage\":\"x\"},\"dataBody\":null}"));

        assertUnavailable();
    }

    @Test
    @DisplayName("5xx 면 503 REGION_004 다")
    void serverErrorIsUnavailable() {
        when(districtClient.getDistrict(CODE)).thenThrow(FeignExceptionFixtures.status(500, "{}"));

        assertUnavailable();
    }

    @Test
    @DisplayName("timeout 이면 503 REGION_004 다")
    void timeoutIsUnavailable() {
        when(districtClient.getDistrict(CODE)).thenThrow(FeignExceptionFixtures.readTimeout());

        assertUnavailable();
    }

    @Test
    @DisplayName("서킷이 열려 있으면 surveillance 를 부르지 않고 503 REGION_004 다")
    void openCircuitIsUnavailableWithoutCall() {
        registry.circuitBreaker(InternalClientSupport.SURVEILLANCE_SERVICE).transitionToOpenState();

        assertUnavailable();
        verify(districtClient, never()).getDistrict(anyString());
        assertThat(registry.circuitBreaker(InternalClientSupport.SURVEILLANCE_SERVICE).getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    @DisplayName("200 인데 본문이 없으면 계약 밖이라 503 이다 — 저장 판단을 할 수 없다")
    void emptyBodyIsUnavailable() {
        when(districtClient.getDistrict(CODE)).thenReturn(Response.success(null));

        assertUnavailable();
    }

    @Test
    @DisplayName("물은 코드와 다른 행정동이 오면 계약 밖이라 503 이다 — 사용자가 고르지 않은 동네를 저장하지 않는다")
    void mismatchedCodeIsUnavailable() {
        when(districtClient.getDistrict(CODE)).thenReturn(Response.success(new DistrictClientResponse("11230520", "다른동", "서울특별시 동대문구", true)));

        assertUnavailable();
    }

    private void assertUnavailable() {
        assertThatThrownBy(() -> adapter.findByCode(CODE))
            .isInstanceOfSatisfying(RegionException.class, e -> assertThat(e.getErrorCode()).isEqualTo(RegionErrorCode.INTERNAL_SERVICE_UNAVAILABLE));
    }
}
