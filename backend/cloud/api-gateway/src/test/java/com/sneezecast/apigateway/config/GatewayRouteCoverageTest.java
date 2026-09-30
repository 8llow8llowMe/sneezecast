package com.sneezecast.apigateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.yaml.snakeyaml.Yaml;

/**
 * 게이트웨이 라우트 yml 을 고정한다. 띄운 컨텍스트에서의 매칭 검사는 {@code ApiGatewayApplicationTests} 가 맡는다.
 *
 * <ul>
 *   <li><b>모든 라우트는 {@code Path=/api/v1/<한 마디>/**} shortcut 하나씩으로만 이뤄진다</b> — 긴 형식(name/args),
 *       Path 가 없는 라우트, 다른 predicate 는 이 검사가 읽지 못하는 모양이라 <b>실패</b>시킨다. 읽지 못해 건너뛰면
 *       {@code /internal/**} 이나 포괄 라우트({@code /**}, {@code /api/v1/**})가 검사를 조용히 빠져나간다.
 *       서비스 간 API 는 게이트웨이 밖에 있다 (architecture-guide §4).</li>
 *   <li><b>선언한 라우트 표와 같다</b> — 접두어마다 어느 서비스로 가는지. 라우트를 늘리면 여기 표도 함께 고친다.
 *       dev · prod 가 같은 표를 보므로 한쪽 yml 만 고치는 실수도 여기서 잡힌다.</li>
 *   <li><b>컨트롤러가 여는 {@code /api/v1/*} 접두어 ⊆ 라우트</b> — 컨트롤러는 서비스 안에서 멀쩡히 동작하는데 게이트웨이
 *       라우트가 빠져 프론트엔드가 도달하지 못하는 실수는 컴파일로도 기동으로도 잡히지 않는다. 게이트웨이가 내는 404 는
 *       "라우트 미등록" 인지 "리소스 없음" 인지 구분되지 않아 한참 뒤에 발견된다. 라우트만 있고 컨트롤러가 아직 없는
 *       경로(미착수 기능)는 프론트가 부를 일이 없어 문제가 아니다.</li>
 * </ul>
 *
 * <p>다른 모듈의 소스를 파일 시스템으로 읽는 것은 이 저장소 레이아웃({@code backend/service/*})에 묶인 선택이다 —
 * 서비스 모듈에 의존을 걸면 게이트웨이가 모든 서비스를 컴파일 시점에 끌어안게 되어 더 나쁘다.
 */
class GatewayRouteCoverageTest {

    /** Gradle 은 모듈 디렉터리에서 테스트를 돌린다. 여기서 두 단계 위가 backend/ 다. */
    private static final Path BACKEND_ROOT = Path.of("..", "..").toAbsolutePath().normalize();
    private static final Path SERVICE_ROOT = BACKEND_ROOT.resolve("service");

    /** {@code @RequestMapping("/api/v1/x")} · {@code (value = "/api/v1/x")} · {@code (path = {"/api/v1/x"})} 의 첫 마디. */
    private static final Pattern REQUEST_MAPPING =
        Pattern.compile("@RequestMapping\\(\\s*(?:(?:value|path)\\s*=\\s*)?\\{?\\s*\"(/api/v1/[^/\"]+)");

    /** 허용하는 유일한 predicate 모양. 마디는 소문자 kebab-case 하나다 — {@code /api/v1/**} 같은 포괄 패턴은 맞지 않는다. */
    private static final Pattern ROUTE_PATH = Pattern.compile("^Path=(/api/v1/[a-z0-9]+(?:-[a-z0-9]+)*)/\\*\\*$");

    /** 접두어 → 라우트 uri. batch-service 는 공개 라우트가 없다. */
    private static final Map<String, String> EXPECTED_ROUTES = Map.of(
        "/api/v1/auth", "lb://${AUTH_SERVICE_APP_NAME}",
        "/api/v1/members", "lb://${AUTH_SERVICE_APP_NAME}",
        "/api/v1/districts", "lb://${SURVEILLANCE_SERVICE_APP_NAME}",
        "/api/v1/reports", "lb://${SURVEILLANCE_SERVICE_APP_NAME}",
        "/api/v1/advisories", "lb://${SURVEILLANCE_SERVICE_APP_NAME}"
    );

    @ParameterizedTest(name = "application-{0}.yml")
    @ValueSource(strings = {"dev", "prod"})
    @DisplayName("라우트는 선언한 표와 정확히 같다 — 접두어도, 가는 서비스도 (읽지 못하는 predicate 가 있으면 실패)")
    void routesMatchTheDeclaredTable(String profile) throws IOException {
        assertThat(routedPrefixes(profile)).as("application-%s.yml 의 라우트", profile).isEqualTo(new TreeMap<>(EXPECTED_ROUTES));
    }

    @ParameterizedTest(name = "application-{0}.yml")
    @ValueSource(strings = {"dev", "prod"})
    @DisplayName("컨트롤러가 여는 /api/v1 접두어는 모두 라우트가 있어야 한다")
    void everyControllerPrefixHasARoute(String profile) throws IOException {
        assertThat(routedPrefixes(profile).keySet())
            .as("컨트롤러가 여는 접두어 중 application-%s.yml 에 라우트가 없는 것", profile)
            .containsAll(controllerPrefixes());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
        "Path=/internal/v1/districts/**",
        "Path=/api/v1/**",
        "Path=/**",
        "Path=/api/v1/reports/**,/internal/v1/reporters/**",
        "Path=/api/v1/reports",
        "Method=GET"
    })
    @DisplayName("허용 모양이 아닌 predicate 는 읽지 않는다 — 검사 자체가 이것들을 통과시키지 않는지 본다")
    void rejectsPredicatesOutsideTheAllowedShape(String predicate) {
        assertThat(ROUTE_PATH.matcher(predicate).matches()).isFalse();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
        "@RequestMapping(\"/api/v1/reports\")",
        "@RequestMapping(value = \"/api/v1/reports\")",
        "@RequestMapping(path = {\"/api/v1/reports\"})",
        "@RequestMapping(\"/api/v1/reports/me\")"
    })
    @DisplayName("컨트롤러 매핑은 문자열 · value = · path = {…} 형태를 모두 읽는다")
    void readsEveryRequestMappingForm(String source) {
        Matcher matcher = REQUEST_MAPPING.matcher(source);

        assertThat(matcher.find()).isTrue();
        assertThat(matcher.group(1)).isEqualTo("/api/v1/reports");
    }

    @Test
    @DisplayName("대조 기준 경로가 backend/ 를 가리킨다 — 틀리면 컨트롤러를 하나도 못 읽고 조용히 통과한다")
    void backendRootIsResolved() {
        assertThat(BACKEND_ROOT.resolve("settings.gradle"))
            .as("backend 루트 계산이 틀렸다 — %s", BACKEND_ROOT)
            .isRegularFile();
    }

    /**
     * 라우트를 접두어 → uri 로 읽는다. <b>모든 라우트의 모든 predicate 가 {@link #ROUTE_PATH} 모양이어야 한다</b> —
     * 아니면 그 자리에서 실패한다. 라우트마다 predicate 는 정확히 하나, 접두어는 라우트끼리 겹치지 않는다.
     */
    private static Map<String, String> routedPrefixes(String profile) throws IOException {
        List<?> routes = routes(profile);
        assertThat(routes).as("application-%s.yml 에 라우트가 있어야 한다", profile).isNotEmpty();

        Map<String, String> routed = new TreeMap<>();
        for (Object rawRoute : routes) {
            Map<?, ?> route = asMap(rawRoute, "routes[]", profile);
            Object id = route.get("id");
            List<?> predicates = asList(route.get("predicates"), "routes[" + id + "].predicates", profile);
            assertThat(predicates).as("application-%s.yml 의 라우트 %s 는 Path predicate 하나만 가진다", profile, id).hasSize(1);

            Object predicate = predicates.get(0);
            assertThat(predicate)
                .as("application-%s.yml 의 라우트 %s — 긴 형식(name/args) predicate 는 쓰지 않는다", profile, id)
                .isInstanceOf(String.class);
            Matcher matcher = ROUTE_PATH.matcher((String) predicate);
            assertThat(matcher.matches())
                .as("application-%s.yml 의 라우트 %s 의 predicate '%s' 는 Path=/api/v1/<마디>/** 여야 한다", profile, id, predicate)
                .isTrue();

            String previous = routed.put(matcher.group(1), String.valueOf(route.get("uri")));
            assertThat(previous).as("application-%s.yml 에서 접두어 %s 가 두 라우트에 있다", profile, matcher.group(1)).isNull();
        }
        return routed;
    }

    /**
     * {@code backend/service/*}/src/main/java 의 *Controller.java 에서 {@code @RequestMapping} 의 {@code /api/v1/...} 첫 마디.
     * 서비스 모듈이 아직 없으면 빈 집합이다.
     *
     * <p>서비스마다 {@code src/main/java} 만 걷는다 — 서비스 루트째 걸으면 {@code build/}(클래스·리포트·캐시)까지
     * 순회해 느려진다.
     */
    private static Set<String> controllerPrefixes() throws IOException {
        Set<String> prefixes = new TreeSet<>();
        for (Path sourceRoot : mainSourceRoots()) {
            try (Stream<Path> files = Files.walk(sourceRoot)) {
                List<Path> controllers = files
                    .filter(path -> path.getFileName().toString().endsWith("Controller.java"))
                    .toList();
                for (Path controller : controllers) {
                    Matcher matcher = REQUEST_MAPPING.matcher(Files.readString(controller, StandardCharsets.UTF_8));
                    while (matcher.find()) {
                        prefixes.add(matcher.group(1));
                    }
                }
            }
        }
        return prefixes;
    }

    private static List<Path> mainSourceRoots() throws IOException {
        if (!Files.isDirectory(SERVICE_ROOT)) {
            return List.of();
        }
        try (Stream<Path> services = Files.list(SERVICE_ROOT)) {
            return services
                .map(service -> service.resolve("src").resolve("main").resolve("java"))
                .filter(Files::isDirectory)
                .toList();
        }
    }

    /**
     * 프로파일 yml 의 {@code spring.cloud.gateway.routes}.
     *
     * <p>구조를 한 단계씩 확인하며 내려간다 — yml 이 깨졌을 때 NPE 대신 "어느 키가 없는지" 가 실패 메시지에 남아야 한다.
     */
    private static List<?> routes(String profile) throws IOException {
        try (InputStream yml = GatewayRouteCoverageTest.class.getResourceAsStream("/application-" + profile + ".yml")) {
            assertThat(yml).as("application-%s.yml 이 클래스패스에 있어야 한다", profile).isNotNull();
            Map<String, Object> root = new Yaml().load(yml);
            return asList(section(section(section(root, "spring", profile), "cloud", profile), "gateway", profile)
                .get("routes"), "spring.cloud.gateway.routes", profile);
        }
    }

    private static Map<?, ?> section(Map<?, ?> parent, String key, String profile) {
        return asMap(parent.get(key), key, profile);
    }

    private static Map<?, ?> asMap(Object value, String name, String profile) {
        assertThat(value).as("application-%s.yml 의 %s 는 매핑이어야 한다", profile, name).isInstanceOf(Map.class);
        return (Map<?, ?>) value;
    }

    private static List<?> asList(Object value, String name, String profile) {
        assertThat(value).as("application-%s.yml 의 %s 는 목록이어야 한다", profile, name).isInstanceOf(List.class);
        return (List<?>) value;
    }
}
