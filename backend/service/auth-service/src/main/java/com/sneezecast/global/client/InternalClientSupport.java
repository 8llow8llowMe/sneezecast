package com.sneezecast.global.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sneezecast.common.dto.Response;
import feign.FeignException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 서비스 간 호출(Feign)의 서킷 적용과 예외 변환 공통 헬퍼 (coding-conventions §9).
 *
 * <p>Feign 호출을 대상 논리명의 서킷으로 감싸 실행하고 {@code Response<T>} 봉투에서 {@code dataBody} 를 꺼낸다. 실패는 두 갈래로만 올린다.
 * <ul>
 *   <li><b>4xx</b> → {@link InternalClientRejectedException}. 상대가 요청을 읽고 거절한 것이라 장애가 아니다 — 서킷에 집계하지 않고
 *       (yml {@code ignore-exceptions} 의 {@code FeignException$FeignClientException}), HTTP 상태와 봉투의 {@code resultCode} 를 보존해 호출
 *       어댑터가 뜻을 정하게 한다 (예: 404 + 리소스 없음 코드 → {@code Optional.empty()}).</li>
 *   <li><b>그 밖</b>(5xx · connect / read timeout · 응답 해석 실패 · 서킷 오픈 {@link CallNotPermittedException}) →
 *       {@link InternalServiceUnavailableException}. 어댑터가 자기 도메인의 {@code INTERNAL_SERVICE_UNAVAILABLE}(503)로 바꾼다.</li>
 * </ul>
 * Feign · resilience4j 타입은 이 헬퍼와 호출 어댑터 밖(application)으로 새지 않는다.
 *
 * <p>OpenFeign 의 서킷 자동 래핑({@code spring.cloud.openfeign.circuitbreaker.enabled})은 켜지 않는다 — 켜면 이 헬퍼와 이중으로 감싸고,
 * Spring Cloud 기본 TimeLimiter(1초)가 Feign read timeout 보다 먼저 끊는다.
 *
 * <p>지금은 auth 만 다른 서비스를 부르므로 auth 의 {@code global} 에 둔다. <b>두 번째로 Feign 을 쓰는 서비스가 생기면 core 로 올린다</b>
 * (예외 두 종과 함께) — 서비스마다 복제하면 4xx · 5xx 판정이 갈라진다.
 */
@Component
@RequiredArgsConstructor
public class InternalClientSupport {

    /** surveillance-service 의 논리명. 서킷 인스턴스명(yml {@code resilience4j.circuitbreaker.instances})이자 Feign 이름 설정 키다. */
    public static final String SURVEILLANCE_SERVICE = "surveillance-service";

    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final ObjectMapper objectMapper;

    /**
     * @param targetService 대상 논리명. 같은 이름의 서킷 인스턴스로 집계한다
     * @param requester     Feign 호출. 봉투째 돌려준다
     * @return 봉투의 {@code dataBody}. 응답 본문이 없으면 null
     * @throws InternalClientRejectedException     상대가 4xx 로 거절했다
     * @throws InternalServiceUnavailableException 상대가 응답하지 못했다 (5xx · timeout · 서킷 오픈 · 응답 해석 실패)
     */
    public <T> T requestAndUnwrap(String targetService, Supplier<Response<T>> requester) {
        Response<T> response;
        try {
            response = circuitBreakerRegistry.circuitBreaker(targetService).executeSupplier(requester);
        } catch (FeignException.FeignClientException exception) {
            throw new InternalClientRejectedException(targetService, exception.status(), resultCodeOf(exception), exception);
        } catch (CallNotPermittedException | FeignException exception) {
            throw new InternalServiceUnavailableException(targetService, exception);
        }
        return response == null ? null : response.dataBody();
    }

    /**
     * 오류 봉투의 {@code dataHeader.resultCode}. 봉투가 아니면(경로가 없는 Spring 기본 404 · 프록시 오류 페이지) null 이다 — 어댑터가 "리소스
     * 없음" 과 "상대가 아직 그 경로를 모른다(옛 버전)" 를 가를 수 있게 한다.
     */
    private String resultCodeOf(FeignException exception) {
        String body = exception.contentUTF8();
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode resultCode = objectMapper.readTree(body).path("dataHeader").path("resultCode");
            return resultCode.isTextual() ? resultCode.asText() : null;
        } catch (JsonProcessingException ignored) {
            return null;
        }
    }
}
