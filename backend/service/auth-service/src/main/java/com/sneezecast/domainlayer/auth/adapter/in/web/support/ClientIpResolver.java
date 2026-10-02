package com.sneezecast.domainlayer.auth.adapter.in.web.support;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * IP 발송 상한의 키로 쓸 클라이언트 IP 를 얻는다.
 *
 * <p>요청은 {@code nginx → api-gateway → auth-service} 순으로 들어온다. nginx 는 {@code X-Real-IP} 를 접속 IP({@code $remote_addr})로
 * <b>덮어쓰고</b>, 게이트웨이는 nginx 에서 온 요청이면 그 헤더를 그대로 넘기고 그 밖의 출발지면 접속 주소로 덮어쓴다
 * (api-gateway {@code TrustedProxyHeaderWebFilter}). 그래서 {@code X-Real-IP} 를 먼저 믿는다.
 *
 * <p>{@code X-Forwarded-For} 는 쓰지 않는다. 앞쪽 값은 클라이언트가 임의로 넣을 수 있어(상한 키를 요청마다 바꿔 우회) 믿을 수 없고,
 * 마지막 값은 게이트웨이가 덧붙인 nginx 의 주소라 모든 사용자가 한 키를 나눠 쓰게 된다. 헤더가 없으면(게이트웨이를 거치지 않은 내부 호출)
 * 접속 주소를 쓴다.
 */
@Component
public class ClientIpResolver {

    static final String X_REAL_IP = "X-Real-IP";

    public String resolve(HttpServletRequest request) {
        String realIp = request.getHeader(X_REAL_IP);
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }
}
