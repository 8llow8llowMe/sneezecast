package com.sneezecast.apigateway.jwt.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * 게이트웨이의 거부 사유가 security-core 의 {@code SecurityErrorCode} 와 <b>같은 말을 하는지</b>
 * 대조한다.
 *
 * <p>같은 만료 토큰이 auth-service(직결)로 가면 {@code SECURITY_002}, 게이트웨이를 거치면
 * 다른 코드로 올 이유가 없다 — 사용자에게 일어난 일은 하나다. 프론트가 코드로 분기하므로 두
 * 체계가 섞이면 분기를 두 벌 갖게 된다.
 *
 * <p><b>왜 의존이 아니라 소스 대조인가.</b> security-core 는 {@code spring-boot-starter-web}(서블릿)
 * 과 Spring Security 를 끌고 온다. WebFlux 인 게이트웨이가 그것을 의존하면 서블릿 스택이 통째로
 * 딸려 들어온다 — 코드 문자열 여섯 개를 공유하자고 치를 대가가 아니다. 그래서 값은 복사하고,
 * 복사본이 어긋나는 것만 여기서 막는다. 다른 모듈의 소스를 파일 시스템으로 읽는 방식은
 * {@code GatewayRouteCoverageTest} 가 이미 쓰는 이 저장소의 수단이다.
 *
 * <p>대조 방향은 한쪽이다: <b>게이트웨이 사유 ⊆ SecurityErrorCode</b>. {@code SECURITY_001}(인증 필요)·
 * {@code SECURITY_006}(권한 없음)은 게이트웨이가 낼 일이 없다 — 토큰이 없으면 그냥 통과시키고
 * 인가 판정도 하지 않는다.
 */
class JwtErrorCodeContractTest {

    /** Gradle 은 모듈 디렉터리에서 테스트를 돌린다. 여기서 두 단계 위가 backend/ 다. */
    private static final Path SECURITY_ERROR_CODE = Path.of("..", "..", "core", "security-core", "src", "main",
        "java", "com", "sneezecast", "security", "common", "exception", "SecurityErrorCode.java")
        .toAbsolutePath().normalize();

    private static final Pattern ENUM_CONSTANT = Pattern.compile(
        "(\\w+)\\(\\s*\"([A-Z_0-9]+)\"\\s*,\\s*\"([^\"]+)\"\\s*,\\s*HttpStatus\\.(\\w+)\\s*\\)");

    @ParameterizedTest
    @EnumSource(JwtErrorCode.class)
    @DisplayName("게이트웨이의 사유는 security-core 와 같은 코드·메시지·상태를 낸다")
    void everyGatewayReasonMatchesSecurityCore(JwtErrorCode gatewayCode) throws IOException {
        Map<String, SecurityReason> securityReasons = securityReasons();

        assertThat(securityReasons)
            .as("대조할 SecurityErrorCode 를 못 읽었으면 경로 계산이 틀린 것이다 — %s", SECURITY_ERROR_CODE)
            .isNotEmpty();
        assertThat(securityReasons)
            .as("게이트웨이의 %s 에 대응하는 SecurityErrorCode 상수가 없다 — 이름이 같아야 한다", gatewayCode.name())
            .containsKey(gatewayCode.name());

        SecurityReason expected = securityReasons.get(gatewayCode.name());
        assertThat(gatewayCode.getResultCode())
            .as("%s 의 resultCode 가 security-core 와 다르다", gatewayCode.name())
            .isEqualTo(expected.code());
        assertThat(gatewayCode.getMessage())
            .as("%s 의 메시지가 security-core 와 다르다 — 같은 코드가 두 문구를 가지면 안 된다", gatewayCode.name())
            .isEqualTo(expected.message());
        assertThat(gatewayCode.getHttpStatus().name())
            .as("%s 의 HTTP 상태가 security-core 와 다르다", gatewayCode.name())
            .isEqualTo(expected.httpStatus());
    }

    /** security-core 의 {@code SecurityErrorCode} 상수를 이름 → (코드, 메시지, 상태) 로 읽는다. */
    private static Map<String, SecurityReason> securityReasons() throws IOException {
        String source = Files.readString(SECURITY_ERROR_CODE, StandardCharsets.UTF_8);
        Matcher matcher = ENUM_CONSTANT.matcher(source);

        Map<String, SecurityReason> reasons = new LinkedHashMap<>();
        while (matcher.find()) {
            reasons.put(matcher.group(1), new SecurityReason(matcher.group(2), matcher.group(3), matcher.group(4)));
        }
        return reasons;
    }

    private record SecurityReason(String code, String message, String httpStatus) {
    }
}
