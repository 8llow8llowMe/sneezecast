package com.sneezecast.apigateway.filter;

import com.sneezecast.apigateway.exception.GatewayErrorCode;
import com.sneezecast.apigateway.exception.GatewayException;
import java.util.List;
import java.util.Locale;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * 경로 우회 문자가 든 요청을 400 {@code GATEWAY_001} 로 거부한다.
 *
 * <h2>무엇을 막나</h2>
 *
 * <p>게이트웨이는 라우트 prefix 를 <b>정규화하지 않은 원문 경로</b>로 맞추고 그대로 업스트림에 넘긴다. 업스트림(Tomcat)은
 * {@code ..;/} · 인코딩된 점 · 슬래시를 정규화하므로, {@code /api/v1/auth/..;/..;/internal/x} 는 게이트웨이에서
 * {@code /api/v1/auth/**} 라우트를 통과한 뒤 서비스에서 {@code /internal/**} 로 풀린다 — 라우팅하지 않는다고 막아 둔
 * 서비스 간 API 에 밖에서 닿는다. {@code %25} 는 이중 인코딩({@code %252e} → {@code %2e} → {@code .}),
 * {@code ;} 는 matrix 파라미터로 마디를 숨기는 우회다. 공개 API 경로에는 이 문자들이 필요 없으므로 하나라도 있으면
 * 이유를 따지지 않고 거부한다. 쿼리스트링은 경로 해석에 쓰이지 않으므로 보지 않는다.
 *
 * <p>UTF-8 다중 바이트 문자의 바이트는 모두 {@code 0x80} 이상이라, 한글 같은 인코딩 경로가 아래 목록에 걸리지 않는다.
 *
 * <h2>GlobalFilter 가 아니라 WebFilter 인 이유</h2>
 *
 * <p>GlobalFilter 는 <b>라우트가 매칭된 뒤에만</b> 돈다. 여기서는 라우트 매칭 전에, 라우트가 없는 요청까지 같은 규칙으로
 * 막는다. 그래서 거부된 요청은 접근 로그(GlobalFilter)에 남지 않고, 예외 핸들러가 남기는 WARN 한 줄이 기록이다.
 */
@Component
public class PathTraversalRejectWebFilter implements WebFilter, Ordered {

    /** 신뢰 프록시 헤더 정리({@code HIGHEST_PRECEDENCE}) 바로 뒤. */
    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 1;

    /**
     * 소문자로 바꾼 원문 경로에 하나라도 들어 있으면 거부한다.
     *
     * <p>리터럴 {@code \} 는 지금은 여기까지 오지 않는다 — {@code java.net.URI} 가 거부해 spring-web 이 봉투 없는 400 을
     * 먼저 낸다. 요청 URI 해석 방식이 바뀌어도 막히도록 목록에 남겨 둔다.
     */
    static final List<String> FORBIDDEN_SEQUENCES = List.of("..", "%2e", "%2f", "%5c", "%25", "%3b", "%00", "\\", ";");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String rawPath = exchange.getRequest().getURI().getRawPath();
        if (rawPath != null && containsForbiddenSequence(rawPath.toLowerCase(Locale.ROOT))) {
            return Mono.error(new GatewayException(GatewayErrorCode.PATH_NOT_ALLOWED));
        }
        return chain.filter(exchange);
    }

    private static boolean containsForbiddenSequence(String lowerCasePath) {
        for (String sequence : FORBIDDEN_SEQUENCES) {
            if (lowerCasePath.contains(sequence)) {
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
