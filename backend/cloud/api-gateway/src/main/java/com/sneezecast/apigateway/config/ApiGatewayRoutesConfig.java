package com.sneezecast.apigateway.config;

import com.sneezecast.apigateway.filter.JwtAuthApiGatewayFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Configuration
@RequiredArgsConstructor
public class ApiGatewayRoutesConfig {

    /**
     * JWT 필터 순서. 접근 로그({@code HIGHEST_PRECEDENCE}) 바로 뒤라 거부도 로그에 남고, 라우팅 필터
     * ({@code ReactiveLoadBalancerClientFilter} 10150, {@code NettyRoutingFilter} {@code Integer.MAX_VALUE})보다 한참 앞이다.
     *
     * <p>순서를 주지 않은 GlobalFilter 는 {@code LOWEST_PRECEDENCE} 로 정렬되어 {@code NettyRoutingFilter} 와 같은 자리에 선다 —
     * 요청이 업스트림으로 나간 <b>뒤에</b> 검증할 수도 있다.
     */
    public static final int JWT_AUTH_FILTER_ORDER = Ordered.HIGHEST_PRECEDENCE + 1;

    // 생성자 DI를 사용하는 이유
    // 1. 이 설정 클래스는 "Gateway 필터들 조립(Composition)"을 담당하는 Config 역할이다.
    // - @Bean 메서드 파라미터 DI는 "Bean을 생성하기 위해 필요한 직접 의존성"에 적합하지만,
    // - 여러 필터를 조합하는 구성(Config)에서는 생성자 DI가 더 직관적이다.

    // 2. 필드/생성자 DI를 사용하면 Config 클래스 단위에서 필요한 의존성이 명확히 드러나고,
    // - 주입 대상 필터가 여러 개로 확장되어도 @Bean 시그니처가 난잡해지지 않는다.

    // 3. 테스트 코드에서도 Config 객체를 쉽게 Mocking/주입해 조립할 수 있어 유지보수가 용이하다.
    private final JwtAuthApiGatewayFilter jwtAuthApiGatewayFilter;

    @Bean
    public GlobalFilter jwtAuthGlobalFilter() {
        return new OrderedGlobalFilter(jwtAuthApiGatewayFilter.apply(new JwtAuthApiGatewayFilter.Config()), JWT_AUTH_FILTER_ORDER);
    }

    /** 게이트웨이는 GlobalFilter 의 순서를 {@link Ordered} 구현으로만 읽는다 — {@code @Bean} 메서드의 {@code @Order} 는 보지 않는다. */
    private record OrderedGlobalFilter(GatewayFilter delegate, int order) implements GlobalFilter, Ordered {

        @Override
        public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
            return delegate.filter(exchange, chain);
        }

        @Override
        public int getOrder() {
            return order;
        }
    }
}
