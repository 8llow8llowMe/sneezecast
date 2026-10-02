package com.sneezecast.apigateway.exception;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * 게이트웨이가 스스로 내는 오류(경로 거부 · 라우트 없음 · 업스트림 불가 · 업스트림 지연)의 사유와 HTTP 상태.
 *
 * <p>{@code SECURITY_00x}({@code JwtErrorCode})와 체계를 따로 둔다 — 그쪽은 서비스도 같은 사실에 같은 코드를 내는
 * 토큰 거부라 security-core 를 따르지만, 이 사유들은 토큰과 무관하고 서비스가 낼 일도 없는 게이트웨이 자체 오류다.
 */
@Getter
@AllArgsConstructor
public enum GatewayErrorCode {

    PATH_NOT_ALLOWED("GATEWAY_001", "허용되지 않는 요청 경로입니다.", HttpStatus.BAD_REQUEST),
    API_NOT_FOUND("GATEWAY_002", "요청한 API 를 찾을 수 없습니다.", HttpStatus.NOT_FOUND),
    SERVICE_UNAVAILABLE("GATEWAY_003", "서비스를 일시적으로 이용할 수 없습니다. 잠시 후 다시 시도해주세요.", HttpStatus.SERVICE_UNAVAILABLE),
    UPSTREAM_TIMEOUT("GATEWAY_004", "서비스 응답이 지연되고 있습니다. 잠시 후 다시 시도해주세요.", HttpStatus.GATEWAY_TIMEOUT);

    private final String resultCode;
    private final String message;
    private final HttpStatus httpStatus;
}
