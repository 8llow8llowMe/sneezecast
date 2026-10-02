package com.sneezecast.global.config;

import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Configuration;

/**
 * 서비스 간 호출(Feign) 클라이언트 스캔. 애플리케이션 클래스가 아니라 여기에 둔다 — 컨텍스트마다 {@code adapter/out/client/feign} 아래
 * {@code @FeignClient} 만 찾게 범위를 좁히고, Feign 을 끄거나 바꿀 때 고칠 곳을 한 군데로 모은다.
 *
 * <p>timeout 은 yml {@code spring.cloud.openfeign.client.config.default}, 서킷은 {@code resilience4j.circuitbreaker} 와
 * {@code InternalClientSupport} 가 맡는다. 클라이언트마다 {@code configuration} · {@code url} 을 붙이지 않는다 (coding-conventions §9).
 */
@Configuration
@EnableFeignClients(basePackages = "com.sneezecast.domainlayer")
public class AuthServiceFeignConfig {

}
