package com.sneezecast.domainlayer.region.adapter.out.client.feign;

import com.sneezecast.common.dto.Response;
import com.sneezecast.domainlayer.region.adapter.out.client.feign.dto.DistrictClientResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * surveillance 행정동 내부 API (architecture-guide §4). 이름은 Eureka 등록명을 설정으로 받는다 — 환경마다 다를 수 있어 하드코딩하지 않는다.
 * timeout 은 yml 공통 설정({@code spring.cloud.openfeign.client.config.default}), 서킷은 {@code InternalClientSupport} 가 맡는다.
 *
 * <p>직접 부르지 않고 {@code DistrictClientAdapter} 를 거친다 — 4xx · 5xx 판정과 서킷이 거기서 붙는다.
 */
@FeignClient(name = "${feign-client.target-services.surveillance-service:surveillance-service}", contextId = "regionDistrictClient")
public interface DistrictClient {

    @GetMapping("/internal/v1/districts/{code}")
    Response<DistrictClientResponse> getDistrict(@PathVariable("code") String code);
}
