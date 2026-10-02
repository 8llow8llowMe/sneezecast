package com.sneezecast.apigateway.filter.properties;

import io.netty.handler.ipfilter.IpFilterRuleType;
import io.netty.handler.ipfilter.IpSubnetFilterRule;
import io.netty.util.NetUtil;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code X-Real-IP} · {@code X-Forwarded-*} 를 믿을 앞단 프록시(nginx) 목록. 항목은 리터럴 IPv4/IPv6 또는 {@code IP/prefix} CIDR 이다.
 *
 * <p>생성 시점에 검사해 <b>비었거나 형식이 틀리면 기동을 실패시킨다.</b> 틀린 값으로 뜨면 nginx 경유 요청의 헤더까지 지워져
 * 모든 요청이 nginx IP 한 키를 나눠 쓰게 된다 — 겉으로는 정상이라 늦게 발견된다.
 *
 * <p><b>호스트명은 받지 않는다.</b> {@link InetAddress#getByName} 이나 Netty 의 문자열 생성자는 호스트명이면 DNS 를 조회한다 —
 * 기동이 DNS 에 묶이고, 조회 결과가 바뀌면 신뢰 대상이 말없이 바뀐다. 그래서 {@link NetUtil} 로 리터럴인지 먼저 확인하고,
 * DNS 를 타지 않는 {@link NetUtil#createInetAddressFromIpAddressString} 로만 주소를 만든다.
 */
@ConfigurationProperties(prefix = "gateway")
public record TrustedProxyProperties(
    List<String> trustedProxies
) {

    private static final String PROPERTY = "gateway.trusted-proxies (GATEWAY_TRUSTED_PROXIES)";
    private static final Pattern PREFIX_LENGTH = Pattern.compile("\\d{1,3}");
    private static final int IPV4_MAX_PREFIX = 32;
    private static final int IPV6_MAX_PREFIX = 128;

    public TrustedProxyProperties {
        if (trustedProxies == null || trustedProxies.isEmpty()) {
            throw new IllegalArgumentException(PROPERTY + " 는 비어 있을 수 없습니다. 앞단 프록시(nginx)의 IP 또는 CIDR 을 콤마로 구분해 넣으세요.");
        }
        List<String> normalized = new ArrayList<>(trustedProxies.size());
        for (String entry : trustedProxies) {
            String trimmed = entry == null ? "" : entry.trim();
            toRule(trimmed); // 형식 검사 — 틀리면 여기서 기동이 멈춘다.
            normalized.add(trimmed);
        }
        trustedProxies = List.copyOf(normalized);
    }

    /** 접속 주소 매칭용 규칙. 필터가 기동 때 한 번 만든다. */
    public List<IpSubnetFilterRule> toRules() {
        return trustedProxies.stream().map(TrustedProxyProperties::toRule).toList();
    }

    /** {@code IP} 는 단일 주소({@code /32} · {@code /128}), {@code IP/prefix} 는 그 대역이다. */
    private static IpSubnetFilterRule toRule(String entry) {
        if (entry.isEmpty()) {
            throw new IllegalArgumentException(PROPERTY + " 에 빈 항목이 있습니다.");
        }
        int slash = entry.indexOf('/');
        String address = slash < 0 ? entry : entry.substring(0, slash);
        InetAddress inetAddress = parseLiteralAddress(address, entry);
        int maxPrefix = inetAddress instanceof Inet4Address ? IPV4_MAX_PREFIX : IPV6_MAX_PREFIX;
        int prefix = slash < 0 ? maxPrefix : parsePrefix(entry.substring(slash + 1), maxPrefix, entry);
        return new IpSubnetFilterRule(inetAddress, prefix, IpFilterRuleType.ACCEPT);
    }

    private static InetAddress parseLiteralAddress(String address, String entry) {
        if (!NetUtil.isValidIpV4Address(address) && !NetUtil.isValidIpV6Address(address)) {
            throw new IllegalArgumentException(PROPERTY + " 항목 '" + entry + "' 는 IP 또는 CIDR 이 아닙니다. 호스트명은 받지 않습니다.");
        }
        InetAddress inetAddress = NetUtil.createInetAddressFromIpAddressString(address);
        if (inetAddress == null) {
            throw new IllegalArgumentException(PROPERTY + " 항목 '" + entry + "' 를 IP 로 읽을 수 없습니다.");
        }
        return inetAddress;
    }

    private static int parsePrefix(String prefix, int maxPrefix, String entry) {
        if (!PREFIX_LENGTH.matcher(prefix).matches() || Integer.parseInt(prefix) > maxPrefix) {
            throw new IllegalArgumentException(PROPERTY + " 항목 '" + entry + "' 의 prefix 는 0~" + maxPrefix + " 이어야 합니다.");
        }
        return Integer.parseInt(prefix);
    }
}
