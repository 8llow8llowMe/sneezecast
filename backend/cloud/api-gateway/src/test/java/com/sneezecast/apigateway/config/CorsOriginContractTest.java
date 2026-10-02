package com.sneezecast.apigateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 게이트웨이 CORS 허용 오리진이 security-core {@code AuthSecurityConfigurer} 와 <b>같은 목록인지</b> 대조한다.
 *
 * <p>auth-service 로 직결하는 구성에서도 같은 출처가 통과해야 한다. 한쪽만 고치면 경로에 따라 같은 화면의 요청이 CORS 로
 * 갈리는데, 컴파일로도 기동으로도 잡히지 않는다. 의존이 아니라 소스 대조인 이유는 {@code JwtErrorCodeContractTest} 와 같다 —
 * security-core 는 서블릿 스택을 끌고 온다.
 *
 * <p>순서는 보지 않는다. 소스에서 {@code setAllowedOriginPatterns(List.of(...))} 를 못 찾으면 <b>실패</b>한다 — 못 읽어서
 * 건너뛰면 대조가 조용히 헛돈다.
 */
class CorsOriginContractTest {

    /** Gradle 은 모듈 디렉터리에서 테스트를 돌린다. 여기서 두 단계 위가 backend/ 다. */
    private static final Path AUTH_SECURITY_CONFIGURER = Path.of("..", "..", "core", "security-core", "src", "main",
        "java", "com", "sneezecast", "security", "auth", "config", "AuthSecurityConfigurer.java")
        .toAbsolutePath().normalize();

    private static final Pattern ORIGIN_PATTERNS_BLOCK =
        Pattern.compile("setAllowedOriginPatterns\\(\\s*List\\.of\\((.*?)\\)\\s*\\)", Pattern.DOTALL);
    private static final Pattern STRING_LITERAL = Pattern.compile("\"([^\"]*)\"");

    @Test
    @DisplayName("게이트웨이 허용 오리진은 security-core AuthSecurityConfigurer 와 같은 목록이다 (순서 무관)")
    void gatewayOriginsMatchSecurityCore() throws IOException {
        assertThat(AUTH_SECURITY_CONFIGURER).as("대조할 소스 경로가 틀렸다 — %s", AUTH_SECURITY_CONFIGURER).isRegularFile();

        assertThat(ApiGatewayCorsConfig.ALLOWED_ORIGIN_PATTERNS)
            .as("security-core 와 다른 오리진이 있다")
            .containsExactlyInAnyOrderElementsOf(securityCoreOrigins());
    }

    @Test
    @DisplayName("오리진 블록 파서는 주석이 섞인 List.of(...) 에서 문자열만 뽑는다 — 대조 자체가 헛돌지 않는다")
    void parsesOriginsBlockWithComments() {
        String source = """
            config.setAllowedOriginPatterns(List.of(
                "https://a.example",   // 웹
                "http://localhost:[*]" // 로컬
            ));
            """;

        assertThat(origins(source)).containsExactly("https://a.example", "http://localhost:[*]");
    }

    private static List<String> securityCoreOrigins() throws IOException {
        List<String> origins = origins(Files.readString(AUTH_SECURITY_CONFIGURER, StandardCharsets.UTF_8));
        assertThat(origins)
            .as("%s 에서 setAllowedOriginPatterns(List.of(...)) 의 문자열을 못 읽었다 — 모양이 바뀌었으면 이 파서를 고친다",
                AUTH_SECURITY_CONFIGURER)
            .isNotEmpty();
        return origins;
    }

    private static List<String> origins(String source) {
        Matcher block = ORIGIN_PATTERNS_BLOCK.matcher(source);
        assertThat(block.find()).as("setAllowedOriginPatterns(List.of(...)) 블록이 없다").isTrue();

        List<String> origins = new ArrayList<>();
        Matcher literal = STRING_LITERAL.matcher(block.group(1));
        while (literal.find()) {
            origins.add(literal.group(1));
        }
        return origins;
    }
}
