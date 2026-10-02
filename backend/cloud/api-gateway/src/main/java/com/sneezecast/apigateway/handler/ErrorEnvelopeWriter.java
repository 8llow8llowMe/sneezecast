package com.sneezecast.apigateway.handler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sneezecast.common.dto.Response;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import reactor.core.publisher.Mono;

/**
 * 게이트웨이 예외 핸들러들이 실패 봉투를 쓰는 두 단계. 핸들러마다 같은 순서를 지키게 한 곳에 둔다.
 *
 * <p>둘로 나눈 것은 순서 규칙 때문이다 — <b>직렬화를 상태 · 헤더보다 먼저</b> 해야, 실패했을 때 응답을 건드리지 않은 채
 * 기본 핸들러에게 넘길 수 있다. 그 사이의 로깅은 핸들러마다 다르므로 각자 한다.
 */
final class ErrorEnvelopeWriter {

    private ErrorEnvelopeWriter() {
    }

    /** {@code Response.fail(resultCode, message)} 를 UTF-8 JSON 바이트로 만든다. 응답에는 손대지 않는다. */
    static byte[] serialize(ObjectMapper objectMapper, String resultCode, String message) throws JsonProcessingException {
        return objectMapper.writeValueAsString(Response.fail(resultCode, message)).getBytes(StandardCharsets.UTF_8);
    }

    /** 상태 · {@code Content-Type: application/json} 을 정하고 본문을 쓴다. */
    static Mono<Void> write(ServerHttpResponse response, HttpStatusCode status, byte[] body) {
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        DataBuffer buffer = response.bufferFactory().wrap(body);
        return response.writeWith(Mono.just(buffer));
    }
}
