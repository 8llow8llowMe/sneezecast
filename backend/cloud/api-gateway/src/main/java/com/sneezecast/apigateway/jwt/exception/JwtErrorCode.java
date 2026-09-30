package com.sneezecast.apigateway.jwt.exception;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * 게이트웨이가 토큰을 거부하는 사유와 그때 나갈 HTTP 상태.
 *
 * <h2>코드 체계는 게이트웨이 것이 아니라 서비스 것을 따른다</h2>
 *
 * <p>{@code resultCode} 는 {@code SECURITY_00x} 다 — security-core 의 {@code SecurityErrorCode} 와
 * <b>같은 코드·같은 메시지·같은 상태</b>를 낸다.
 *
 * <p><b>같은 사실에 두 체계를 두면 프론트가 분기를 두 벌 갖게 된다.</b> 같은 만료 토큰이
 * auth-service(직결)로 가면 {@code SECURITY_002}, 게이트웨이를 거치면 다른 코드로 올 이유가 없다 —
 * 사용자에게 일어난 일은 하나다.
 *
 * <p>게이트웨이는 security-core 에 의존하지 않는다 — 그쪽은 {@code spring-boot-starter-web}(서블릿)
 * 과 Spring Security 를 끌고 오는데 이 모듈은 WebFlux 다. 그래서 코드 문자열을 여기 복사해 두고,
 * <b>두 곳이 어긋나지 않는지는 테스트가 지킨다</b> — {@code JwtErrorCodeContractTest} 가
 * security-core 의 소스를 직접 읽어 대조한다 ({@code GatewayRouteCoverageTest} 와 같은 방식이다).
 *
 * <p>{@code SECURITY_001}(인증 필요)·{@code SECURITY_006}(권한 없음)은 여기 없다. 게이트웨이는
 * 토큰이 <b>없으면 그냥 통과시키고</b>(공개 API 가 있으므로) 인가 판정도 하지 않는다 — 둘 다
 * 서비스가 내는 코드다.
 */
@Getter
@AllArgsConstructor
public enum JwtErrorCode {

    TOKEN_EXPIRED("SECURITY_002", "토큰이 만료되었습니다.", HttpStatus.UNAUTHORIZED),
    TOKEN_INVALID("SECURITY_003", "토큰이 유효하지 않습니다.", HttpStatus.UNAUTHORIZED),
    TOKEN_SIGNATURE_INVALID("SECURITY_004", "토큰의 서명 검증에 실패하였습니다.", HttpStatus.UNAUTHORIZED),
    TOKEN_MALFORMED("SECURITY_005", "토큰 형식이 올바르지 않습니다.", HttpStatus.UNAUTHORIZED),
    TOKEN_REVOKED("SECURITY_007", "로그아웃된 토큰입니다. 다시 로그인해주세요.", HttpStatus.UNAUTHORIZED),
    TOKEN_VERIFICATION_UNAVAILABLE("SECURITY_008", "일시적으로 토큰 검증을 수행할 수 없습니다. 잠시 후 다시 시도해주세요.", HttpStatus.SERVICE_UNAVAILABLE);

    private final String resultCode;
    private final String message;
    private final HttpStatus httpStatus;
}
