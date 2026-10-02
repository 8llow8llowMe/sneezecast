package com.sneezecast.apigateway.filter;

import static org.assertj.core.api.Assertions.assertThat;

import com.sneezecast.apigateway.filter.properties.TrustedProxyProperties;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 신뢰 프록시가 아닌 출발지의 IP · 전달 헤더 정리. 체인에 넘어간 요청의 헤더를 본다 — 업스트림이 받는 것이 그것이다.
 */
class TrustedProxyHeaderWebFilterTest {

    private static final String NGINX = "192.168.0.12";
    private static final String SPOOFED_IP = "203.0.113.99";

    @Test
    @DisplayName("정확한 IP 로 등록한 nginx 에서 온 요청은 헤더를 그대로 둔다 — nginx 가 덮어쓴 X-Real-IP 가 실제 클라이언트다")
    void keepsHeadersFromTrustedProxy() {
        HttpHeaders forwarded = run(List.of(NGINX), address(NGINX));

        assertThat(forwarded.getFirst("X-Real-IP")).isEqualTo(SPOOFED_IP);
        assertThat(forwarded.getFirst("X-Forwarded-For")).isEqualTo(SPOOFED_IP + ", " + NGINX);
        assertThat(forwarded.getFirst("X-Forwarded-Proto")).isEqualTo("https");
    }

    @Test
    @DisplayName("CIDR 로 등록한 대역 안에서 온 요청도 신뢰한다")
    void trustsAddressInsideCidr() {
        HttpHeaders forwarded = run(List.of("10.0.0.0/8", "192.168.0.0/24"), address("192.168.0.77"));

        assertThat(forwarded.getFirst("X-Real-IP")).isEqualTo(SPOOFED_IP);
    }

    @Test
    @DisplayName("같은 LAN 에서 게이트웨이로 직접 온 요청은 전달 헤더 7개를 지우고 X-Real-IP 를 접속 주소로 덮어쓴다 — auth IP 발송 제한 우회를 막는다")
    void rewritesHeadersFromUntrustedSource() {
        HttpHeaders forwarded = run(List.of(NGINX), address("192.168.0.50"));

        assertThat(forwarded.get("X-Real-IP")).containsExactly("192.168.0.50");
        assertThat(forwarded.keySet()).doesNotContain(
            "X-Forwarded-For", "Forwarded", "X-Forwarded-Host", "X-Forwarded-Proto", "X-Forwarded-Port", "X-Forwarded-Prefix");
        assertThat(forwarded.getFirst("User-Agent")).as("전달 헤더가 아닌 것은 건드리지 않는다").isEqualTo("test-agent");
    }

    @Test
    @DisplayName("대역 바로 밖의 주소는 신뢰하지 않는다")
    void rejectsAddressOutsideCidr() {
        HttpHeaders forwarded = run(List.of("192.168.0.0/25"), address("192.168.0.128"));

        assertThat(forwarded.get("X-Real-IP")).containsExactly("192.168.0.128");
    }

    @Test
    @DisplayName("접속 주소를 모르면 전달 헤더를 지우기만 한다 — 위조 값을 남기지 않는다")
    void removesHeadersWhenRemoteAddressIsUnknown() {
        HttpHeaders forwarded = run(List.of(NGINX), null);

        assertThat(forwarded.keySet()).doesNotContainAnyElementsOf(TrustedProxyHeaderWebFilter.FORWARDING_HEADERS);
    }

    @Test
    @DisplayName("IPv6 프록시와 접속 주소도 같은 규칙이다")
    void handlesIpv6() throws UnknownHostException {
        HttpHeaders trusted = run(List.of("2001:db8::/32"), new InetSocketAddress(InetAddress.getByName("2001:db8::5"), 50000));
        assertThat(trusted.getFirst("X-Real-IP")).isEqualTo(SPOOFED_IP);

        HttpHeaders untrusted = run(List.of("2001:db8::/32"), new InetSocketAddress(InetAddress.getByName("2001:db9::5"), 50000));
        assertThat(untrusted.get("X-Real-IP")).containsExactly("2001:db9:0:0:0:0:0:5");
        assertThat(untrusted.keySet()).doesNotContain("X-Forwarded-For");
    }

    @Test
    @DisplayName("IPv4 대역은 IPv6 접속 주소에 걸리지 않는다")
    void ipv4RuleDoesNotMatchIpv6Remote() throws UnknownHostException {
        HttpHeaders forwarded = run(List.of("0.0.0.0/0"), new InetSocketAddress(InetAddress.getByName("2001:db8::5"), 50000));

        assertThat(forwarded.get("X-Real-IP")).containsExactly("2001:db8:0:0:0:0:0:5");
    }

    /** 위조 헤더를 모두 실은 요청을 필터에 통과시키고, 체인이 받은 요청의 헤더를 돌려준다. */
    private static HttpHeaders run(List<String> trustedProxies, InetSocketAddress remoteAddress) {
        TrustedProxyHeaderWebFilter filter = new TrustedProxyHeaderWebFilter(new TrustedProxyProperties(trustedProxies));
        MockServerHttpRequest.BaseBuilder<?> request = MockServerHttpRequest.get("/api/v1/auth/email-verifications")
            .header("X-Real-IP", SPOOFED_IP)
            .header("X-Forwarded-For", SPOOFED_IP + ", " + NGINX)
            .header("Forwarded", "for=" + SPOOFED_IP)
            .header("X-Forwarded-Host", "evil.example")
            .header("X-Forwarded-Proto", "https")
            .header("X-Forwarded-Port", "443")
            .header("X-Forwarded-Prefix", "/internal")
            .header("User-Agent", "test-agent");
        if (remoteAddress != null) {
            request.remoteAddress(remoteAddress);
        }

        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();
        filter.filter(MockServerWebExchange.from(request.build()), exchange -> {
            forwarded.set(exchange);
            return Mono.empty();
        }).block();

        assertThat(forwarded.get()).as("이 필터는 요청을 막지 않는다").isNotNull();
        return forwarded.get().getRequest().getHeaders();
    }

    private static InetSocketAddress address(String ipv4) {
        return new InetSocketAddress(ipv4, 50000);
    }
}
