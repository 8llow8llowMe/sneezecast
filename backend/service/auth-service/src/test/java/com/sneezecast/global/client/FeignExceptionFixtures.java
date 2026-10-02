package com.sneezecast.global.client;

import feign.FeignException;
import feign.Request;
import feign.RequestTemplate;
import feign.RetryableException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Feign 이 실제로 던지는 예외를 만든다. WireMock 없이 {@code FeignException.errorStatus}(기본 ErrorDecoder 가 쓰는 팩토리)로 상태별 하위 타입
 * (4xx → {@code FeignClientException}, 5xx → {@code FeignServerException})을 그대로 얻는다.
 */
public final class FeignExceptionFixtures {

    public static final String DISTRICT_NOT_FOUND_BODY = """
        {"dataHeader":{"success":false,"resultCode":"DISTRICT_001","resultMessage":"존재하지 않는 행정동입니다.","fieldErrors":null},"dataBody":null}""";
    /** 경로가 없을 때 Spring 기본 오류 본문 — 봉투가 아니다. */
    public static final String SPRING_DEFAULT_NOT_FOUND_BODY = """
        {"timestamp":"2026-10-02T00:00:00.000+00:00","status":404,"error":"Not Found","path":"/internal/v1/districts/11230510"}""";

    private static final Request REQUEST = Request.create(Request.HttpMethod.GET, "http://surveillance-service/internal/v1/districts/11230510", Map.of(),
        null, StandardCharsets.UTF_8, new RequestTemplate());

    private FeignExceptionFixtures() {
    }

    public static FeignException status(int status, String body) {
        feign.Response response = feign.Response.builder()
            .status(status)
            .reason("test")
            .request(REQUEST)
            .headers(Map.of())
            .body(body, StandardCharsets.UTF_8)
            .build();
        return FeignException.errorStatus("DistrictClient#getDistrict(String)", response);
    }

    /** read timeout — Feign 은 상태 없이(-1) {@link RetryableException} 으로 올린다. */
    public static RetryableException readTimeout() {
        return new RetryableException(-1, "Read timed out", Request.HttpMethod.GET, new SocketTimeoutException("Read timed out"), (Long) null, REQUEST);
    }

    /** yml 과 같은 판정(4xx 무시)에 창만 작게 줄인 레지스트리 — 실패 2건이면 열린다. */
    public static CircuitBreakerRegistry smallRegistry() {
        return CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
            .slidingWindowType(SlidingWindowType.COUNT_BASED)
            .slidingWindowSize(2)
            .minimumNumberOfCalls(2)
            .failureRateThreshold(50)
            .ignoreExceptions(FeignException.FeignClientException.class)
            .build());
    }
}
