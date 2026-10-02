package com.sneezecast.apigateway.filter;

import com.sneezecast.apigateway.filter.properties.TrustedProxyProperties;
import io.netty.handler.ipfilter.IpSubnetFilterRule;
import java.net.InetSocketAddress;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * 신뢰 프록시(nginx)가 아닌 곳에서 온 요청의 클라이언트 IP · 전달 헤더를 지우고, {@code X-Real-IP} 를 실제 접속 주소로 덮어쓴다.
 *
 * <h2>무엇을 막나</h2>
 *
 * <p>auth-service 는 {@code X-Real-IP} 를 클라이언트 IP 로 믿고 IP 단위 발송 제한의 키로 쓴다. nginx 를 거치면 nginx 가 이 값을
 * 접속 주소로 덮어쓰지만, 게이트웨이 포트(3000)는 같은 LAN 에 열려 있다. 거기서 직접 위조한 {@code X-Real-IP} 를 보내면
 * 요청마다 다른 IP 로 보여 제한을 우회한다 (#56 리뷰). 그래서 nginx 가 아닌 출발지면 이 헤더를 접속 주소로 바꿔
 * auth 가 그대로 실제 접속 IP 를 키로 쓰게 한다. {@code X-Forwarded-*} · {@code Forwarded} 도 같은 이유로 지운다 —
 * 지금 믿는 코드가 없어도 업스트림에 위조 값이 닿으면 언젠가 믿는 코드가 생긴다.
 *
 * <p>접속 주소를 알 수 없으면 지우기만 한다. 접속 주소 판정은 {@code server.forward-headers-strategy} 가 꺼져 있다는 전제다 —
 * 켜면 접속 주소가 클라이언트가 보낸 헤더 값으로 바뀌어 이 판정이 무너진다.
 *
 * <h2>운영 주의</h2>
 *
 * <p>{@code GATEWAY_TRUSTED_PROXIES} 에 nginx 가 빠지거나 틀린 주소가 들어가면, nginx 경유 요청도 비신뢰로 보고 {@code X-Real-IP} 를
 * nginx 주소로 덮어쓴다 — <b>모든 사용자가 nginx IP 한 키를 나눠 써서</b> IP 발송 제한이 서비스 전체에 한꺼번에 걸린다.
 *
 * <p>순서는 {@code HIGHEST_PRECEDENCE} 다 — 경로 거부 필터보다 앞이고, WebFilter 라 접근 로그(GlobalFilter)보다도 앞이라
 * 접근 로그의 {@code clientIp} 도 정리된 값을 본다.
 */
@Component
public class TrustedProxyHeaderWebFilter implements WebFilter, Ordered {

    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE;

    static final String X_REAL_IP = "X-Real-IP";

    /** 비신뢰 출발지에서 지우는 헤더. {@code X-Real-IP} 는 지운 뒤 접속 주소로 다시 넣는다. */
    static final List<String> FORWARDING_HEADERS = List.of(
        "X-Forwarded-For",
        X_REAL_IP,
        "Forwarded",
        "X-Forwarded-Host",
        "X-Forwarded-Proto",
        "X-Forwarded-Port",
        "X-Forwarded-Prefix"
    );

    private final List<IpSubnetFilterRule> trustedProxies;

    public TrustedProxyHeaderWebFilter(TrustedProxyProperties properties) {
        this.trustedProxies = properties.toRules();
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        InetSocketAddress remoteAddress = exchange.getRequest().getRemoteAddress();
        boolean knownRemote = remoteAddress != null && remoteAddress.getAddress() != null;
        if (knownRemote && isTrusted(remoteAddress)) {
            return chain.filter(exchange);
        }

        String clientIp = knownRemote ? remoteAddress.getAddress().getHostAddress() : null;
        ServerHttpRequest sanitized = exchange.getRequest().mutate()
            .headers(headers -> {
                FORWARDING_HEADERS.forEach(headers::remove);
                if (clientIp != null) {
                    headers.set(X_REAL_IP, clientIp);
                }
            })
            .build();
        return chain.filter(exchange.mutate().request(sanitized).build());
    }

    private boolean isTrusted(InetSocketAddress remoteAddress) {
        for (IpSubnetFilterRule rule : trustedProxies) {
            if (rule.matches(remoteAddress)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
