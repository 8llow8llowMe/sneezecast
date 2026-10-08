package com.sneezecast.domainlayer.member.adapter.out.client.feign;

import com.sneezecast.common.dto.Response;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * surveillance 원시 보고 파기 내부 API (architecture-guide §4). 받는 쪽은 멱등이고 본문 없는 204 를 낸다 — 디코딩 결과가 null 이고 그것이 성공이다.
 * 이름은 Eureka 등록명을 설정으로 받고, 같은 대상을 부르는 {@code DistrictClient} 와 빈 이름이 겹치지 않게 {@code contextId} 를 따로 둔다.
 * timeout 은 yml 공통 설정, 서킷은 {@code InternalClientSupport} 가 맡는다.
 *
 * <p>Authorization 헤더를 싣지 않는다 — 받는 쪽은 서비스 간 토큰 없이 네트워크 격리에 기대고, 유효하지 않은 Bearer 를 실으면 resource server 가
 * 401 을 낸다. 직접 부르지 않고 {@code ReportPurgeClientAdapter} 를 거친다.
 */
@FeignClient(name = "${feign-client.target-services.surveillance-service:surveillance-service}", contextId = "memberReportPurgeClient")
public interface ReportPurgeClient {

    @DeleteMapping("/internal/v1/reporters/{memberId}")
    Response<Void> purgeReporter(@PathVariable("memberId") long memberId);
}
