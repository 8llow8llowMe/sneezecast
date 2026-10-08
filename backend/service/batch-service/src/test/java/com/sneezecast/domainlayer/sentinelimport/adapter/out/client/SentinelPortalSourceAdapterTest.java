package com.sneezecast.domainlayer.sentinelimport.adapter.out.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sneezecast.domainlayer.official.domain.enums.OfficialAgeGroup;
import com.sneezecast.domainlayer.official.domain.model.KdcaWeek;
import com.sneezecast.domainlayer.sentinelimport.application.exception.SentinelImportErrorCode;
import com.sneezecast.domainlayer.sentinelimport.application.exception.SentinelImportException;
import com.sneezecast.domainlayer.sentinelimport.application.model.SentinelCallBudget;
import com.sneezecast.domainlayer.sentinelimport.domain.model.SentinelFetch;
import com.sneezecast.domainlayer.sentinelimport.domain.model.SentinelIliRow;
import com.sneezecast.domainlayer.sentinelimport.domain.model.SentinelPathogenRow;
import com.sneezecast.domainlayer.sentinelimport.domain.model.SentinelProgram;
import com.sneezecast.domainlayer.sentinelimport.domain.model.SentinelRequest;
import com.sneezecast.global.properties.SentinelPortalProperties;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseActions;
import org.springframework.web.client.RestClient;

/**
 * 응답 본문은 2026-10-02 감염병포털 실호출 원본이다 ({@code src/test/resources/sentinelimport/*.json} — 공공누리 제4유형이라 값을 바꾸지 않고 그대로
 * 둔다). 형식이 어긋난 응답은 원본을 테스트 안에서 고쳐 만든다. 실제 포털은 부르지 않고, 요청 간격은 가짜 시계 · 대기로 잰다.
 *
 * <ul>
 *   <li>{@code ari.json} · {@code gstrnftn.json} — 2026년 35 ~ 39주 (급성호흡기 12열 · 장관감염증 21열)</li>
 *   <li>{@code influ.json} — 2026–2027 절기 36 ~ 40주, 40주는 {@code 집계 중}</li>
 *   <li>{@code influ-prev.json} — 2025–2026 절기 52열, 새해 이후 열의 {@code GR2} 는 54 ~ 88</li>
 * </ul>
 */
@ExtendWith(OutputCaptureExtension.class)
class SentinelPortalSourceAdapterTest {

    private static final String BASE_URL = "https://portal.test/pot/is/st";
    private static final String SESSION_ID = "fake-session-0123";
    private static final String CLIENT_ID = "fake-client-4567";
    private static final String COOKIE = "JSESSIONID=" + SESSION_ID + "; clientid=" + CLIENT_ID;
    private static final MediaType FORM_UTF8 = MediaType.parseMediaType("application/x-www-form-urlencoded;charset=UTF-8");
    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder().build();

    private static final SentinelRequest ARI = SentinelRequest.weeks(SentinelProgram.ARI, new KdcaWeek(2026, 35), new KdcaWeek(2026, 39));
    private static final SentinelRequest ENTERIC = SentinelRequest.weeks(SentinelProgram.ENTERIC, new KdcaWeek(2026, 35), new KdcaWeek(2026, 39));
    private static final SentinelRequest INFLUENZA = SentinelRequest.season(2026);
    private static final String ARI_FORM = "startYear=2026&startWeek=35&endYear=2026&endWeek=39&dayCheck=1&infectiousGubun=&subInfectious=&age=";
    private static final String INFLUENZA_FORM = "startYear=2026&endYear=2027&age=&intoDivi=1&sido=";

    /** {@code ari-cross.json}(2025-50 ~ 2026-03, 2026-10-02 실호출)에서 연도 경계의 두 행만 잘라 옮겼다. 값은 원본 그대로다. */
    private static final String ARI_CROSS_ROWS = """
        [{"COLUMN9":"25","COLUMN8":"311","COLUMN7":"311","COLUMN6":"57","COLUMN10":"65","COLUMN12":"66","COLUMN11":"451","SUBTITLE":"52",\
        "COLUMN5":"15","COLUMN4":"62","TITLE":"2025","COLUMN3":"4","COLUMN2":"28","COLUMN1":"1,395"},
         {"COLUMN9":"41","COLUMN8":"261","COLUMN7":"328","COLUMN6":"64","COLUMN10":"73","COLUMN12":"60","COLUMN11":"419","SUBTITLE":"01",\
        "COLUMN5":"13","COLUMN4":"79","TITLE":"2026","COLUMN3":"6","COLUMN2":"26","COLUMN1":"1,370"}]""";

    /**
     * {@code ari-current.json}(2026-37 ~ 2026-41 요청, 2026-10-06 실호출)의 {@code data} 전체다. 값은 원본 그대로다 — 끝났지만 미공표인 40주는
     * 모든 칸이 {@code 집계 중} 이고, 진행 중인 41주는 행이 없다. {@code headerList} · {@code captionList} 는 {@code ari.json} 과 같았다.
     */
    private static final String ARI_CURRENT_ROWS = """
        [{"COLUMN9":"46","COLUMN8":"297","COLUMN7":"51","COLUMN6":"219","COLUMN10":"21","COLUMN12":"308","COLUMN11":"428","SUBTITLE":"37",\
        "COLUMN5":"21","COLUMN4":"90","TITLE":"2026","COLUMN3":"7","COLUMN2":"32","COLUMN1":"1,520"},
         {"COLUMN9":"47","COLUMN8":"296","COLUMN7":"42","COLUMN6":"160","COLUMN10":"29","COLUMN12":"337","COLUMN11":"438","SUBTITLE":"38",\
        "COLUMN5":"15","COLUMN4":"68","TITLE":"2026","COLUMN3":"4","COLUMN2":"25","COLUMN1":"1,461"},
         {"COLUMN9":"45","COLUMN8":"202","COLUMN7":"27","COLUMN6":"113","COLUMN10":"19","COLUMN12":"248","COLUMN11":"401","SUBTITLE":"39",\
        "COLUMN5":"6","COLUMN4":"78","TITLE":"2026","COLUMN3":"6","COLUMN2":"17","COLUMN1":"1,162"},
         {"COLUMN9":"집계 중","COLUMN8":"집계 중","COLUMN7":"집계 중","COLUMN6":"집계 중","COLUMN10":"집계 중","COLUMN12":"집계 중","COLUMN11":"집계 중",\
        "SUBTITLE":"40","COLUMN5":"집계 중","COLUMN4":"집계 중","TITLE":"2026","COLUMN3":"집계 중","COLUMN2":"집계 중","COLUMN1":"집계 중"}]""";

    /**
     * {@code influ-future.json}(아직 시작하지 않은 2027–2028 절기, 2026-10-06 실호출) 본문 전체다. 줄만 나눴고 바이트는 원본과 같다 — 열 제목이
     * 비고 행에 {@code COLUMN} 이 없다.
     */
    private static final String INFLU_FUTURE = """
        {"ipAddr":null,"paramMap":{},"result":true,"value":{"headerList":[],"data":[{"TITLE":"0세","LV":1,"VALUE":"````````````````","WEEKCNT":0},\
        {"TITLE":"1-6세","LV":2,"VALUE":"````````````````","WEEKCNT":0},{"TITLE":"7-12세","LV":4,"VALUE":"````````````````","WEEKCNT":0},\
        {"TITLE":"13-18세","LV":5,"VALUE":"````````````````","WEEKCNT":0},{"TITLE":"19-49세","LV":7,"VALUE":"````````````````","WEEKCNT":0},\
        {"TITLE":"50-64세","LV":8,"VALUE":"````````````````","WEEKCNT":0},{"TITLE":"65세 이상","LV":9,"VALUE":"````````````````","WEEKCNT":0}],\
        "captionList":[],"paramMap":{"startYear":"2027","sido":"","endYear":"2028","age":"","intoDivi":"1"}},"message":null}""";

    private MockRestServiceServer server;
    private SentinelPortalSourceAdapter adapter;
    private SentinelCallBudget budget;
    private FakeClock clock;
    private List<Duration> sleeps;

    @BeforeEach
    void setUp() {
        clock = new FakeClock(Instant.parse("2026-10-02T21:00:00Z"));
        sleeps = new ArrayList<>();
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        adapter = new SentinelPortalSourceAdapter(builder.build(), properties(), clock, duration -> {
            sleeps.add(duration);
            clock.advance(duration);
        });
        budget = new SentinelCallBudget(12);
    }

    @Test
    @DisplayName("급성호흡기 — 화면 쿠키를 데이터 요청에 싣고, 5주 × 12열을 열 순서의 코드 · 원천 분류 · 이름 · 쉼표 없는 값으로 읽는다")
    void fetchesAriPathogens(CapturedOutput output) {
        byte[] body = fixture("ari.json");
        expectScreen("ari");
        expectData("ari", ARI_FORM).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        SentinelFetch<SentinelPathogenRow> fetch = adapter.fetchPathogens(ARI, budget);

        server.verify();
        assertThat(fetch.rows()).hasSize(60);
        assertThat(fetch.rows().get(0)).isEqualTo(new SentinelPathogenRow(2026, 35, "TOTAL", "계", "계", new BigDecimal("1105")));
        assertThat(fetch.rows()).contains(
            new SentinelPathogenRow(2026, 37, "TOTAL", "계", "계", new BigDecimal("1520")),
            // 원천 분류의 오타(인를루엔자)를 그대로 싣는다.
            new SentinelPathogenRow(2026, 37, "ND0001", "인플루엔자 바이러스", "인를루엔자", new BigDecimal("428")),
            new SentinelPathogenRow(2026, 37, "ND0022", "코로나19 바이러스", "코로나19", new BigDecimal("308")),
            new SentinelPathogenRow(2026, 39, "ND0708", "마이코플라즈마균", "세균", new BigDecimal("17")));
        assertThat(fetch.rows()).extracting(SentinelPathogenRow::week).containsOnly(35, 36, 37, 38, 39);
        assertThat(fetch.rawRowCount()).isEqualTo(5);
        assertThat(fetch.calls()).isEqualTo(2);
        assertThat(fetch.nullValueCount()).isZero();
        assertThat(fetch.pendingCount()).isZero();
        assertThat(fetch.byteLength()).isEqualTo(body.length);
        assertThat(fetch.contentSha256()).isEqualTo(sha256(body));
        assertThat(budget.used()).isEqualTo(2);
        // 화면 다음 데이터 요청 전에 3초를 기다린다. 첫 호출은 기다리지 않는다.
        assertThat(sleeps).containsExactly(Duration.ofSeconds(3));
        // 건수만 남기고 값 · 쿠키는 남기지 않는다.
        assertThat(output).contains("Sentinel portal fetched. operation=ari(2026-35~2026-39) rows=60 rawRows=5 calls=2");
        assertThat(output.toString()).doesNotContain("1,105").doesNotContain(SESSION_ID);
    }

    @Test
    @DisplayName("장관감염증 — 21열을 계 · ND0601 ~ ND0620 으로 읽는다")
    void fetchesEntericPathogens() {
        expectScreen("gstrnftn");
        expectData("gstrnftn", ARI_FORM).andRespond(withSuccess(fixture("gstrnftn.json"), MediaType.APPLICATION_JSON));

        SentinelFetch<SentinelPathogenRow> fetch = adapter.fetchPathogens(ENTERIC, budget);

        server.verify();
        assertThat(fetch.rows()).hasSize(105);
        assertThat(fetch.rows()).contains(
            new SentinelPathogenRow(2026, 39, "TOTAL", "계", "계", new BigDecimal("499")),
            new SentinelPathogenRow(2026, 35, "ND0607", "클로스트리듐 퍼프린젠스", "세균", new BigDecimal("3")),
            new SentinelPathogenRow(2026, 35, "ND0615", "노로바이러스", "바이러스", new BigDecimal("74")),
            new SentinelPathogenRow(2026, 38, "ND0620", "원포자충", "원충", new BigDecimal("1")));
        assertThat(fetch.rows()).extracting(SentinelPathogenRow::diseaseKey).doesNotContain("ND0621");
    }

    @Test
    @DisplayName("인플루엔자 — 7개 연령대 × 5주를 읽고, 진행 중인 40주(집계 중 7칸)는 행을 만들지 않고 센다")
    void fetchesInfluenzaAndSkipsPendingWeek(CapturedOutput output) {
        expectScreen("influ");
        expectData("influ", INFLUENZA_FORM).andRespond(withSuccess(fixture("influ.json"), MediaType.APPLICATION_JSON));

        SentinelFetch<SentinelIliRow> fetch = adapter.fetchInfluenza(INFLUENZA, budget);

        server.verify();
        assertThat(fetch.rows()).hasSize(28);
        assertThat(fetch.pendingCount()).isEqualTo(7);
        assertThat(fetch.nullValueCount()).isZero();
        assertThat(fetch.rawRowCount()).isEqualTo(7);
        assertThat(fetch.rows()).extracting(SentinelIliRow::week).containsOnly(36, 37, 38, 39);
        assertThat(fetch.rows()).extracting(SentinelIliRow::year).containsOnly(2026);
        assertThat(fetch.rows()).contains(
            new SentinelIliRow(2026, 36, OfficialAgeGroup.AGE_0, new BigDecimal("24")),
            new SentinelIliRow(2026, 37, OfficialAgeGroup.AGE_0, new BigDecimal("26.8")),
            new SentinelIliRow(2026, 38, OfficialAgeGroup.AGE_1_6, new BigDecimal("58")),
            new SentinelIliRow(2026, 39, OfficialAgeGroup.AGE_65_PLUS, new BigDecimal("14.6")));
        assertThat(fetch.rows()).extracting(SentinelIliRow::ageGroup).doesNotContain(OfficialAgeGroup.ALL);
        assertThat(output).contains("operation=influ(2026-2027) nullValues=0 pending=7");
    }

    @Test
    @DisplayName("지난 절기 — GR2 52 는 시작 연도 52주, GR2 54 는 끝 연도 1주, GR2 88 은 끝 연도 35주다 (53주 없는 해라 GR2 53 은 없다)")
    void readsPreviousSeasonAcrossNewYear() {
        expectScreen("influ");
        expectData("influ", "startYear=2025&endYear=2026&age=&intoDivi=1&sido=")
            .andRespond(withSuccess(fixture("influ-prev.json"), MediaType.APPLICATION_JSON));

        SentinelFetch<SentinelIliRow> fetch = adapter.fetchInfluenza(SentinelRequest.season(2025), budget);

        assertThat(fetch.rows()).hasSize(52 * 7);
        assertThat(fetch.pendingCount()).isZero();
        assertThat(fetch.rows()).contains(
            new SentinelIliRow(2025, 36, OfficialAgeGroup.AGE_0, new BigDecimal("2.8")),
            new SentinelIliRow(2025, 52, OfficialAgeGroup.AGE_0, new BigDecimal("26.2")),
            new SentinelIliRow(2026, 1, OfficialAgeGroup.AGE_0, new BigDecimal("20.8")),
            new SentinelIliRow(2026, 1, OfficialAgeGroup.AGE_65_PLUS, new BigDecimal("9.3")),
            new SentinelIliRow(2026, 35, OfficialAgeGroup.AGE_0, new BigDecimal("6.8")));
        assertThat(fetch.rows()).filteredOn(row -> row.year() == 2025).extracting(SentinelIliRow::week).allSatisfy(week -> assertThat(week).isBetween(36, 52));
        assertThat(fetch.rows()).filteredOn(row -> row.year() == 2026).extracting(SentinelIliRow::week).allSatisfy(week -> assertThat(week).isBetween(1, 35));
    }

    @Test
    @DisplayName("급성호흡기는 연도를 넘는 범위를 한 번에 받는다 — 2025년 52주 다음이 2026년 1주다")
    void readsAriRangeAcrossYearBoundary() {
        SentinelRequest request = SentinelRequest.weeks(SentinelProgram.ARI, new KdcaWeek(2025, 52), new KdcaWeek(2026, 1));
        expectScreen("ari");
        expectData("ari", "startYear=2025&startWeek=52&endYear=2026&endWeek=01&dayCheck=1&infectiousGubun=&subInfectious=&age=")
            .andRespond(withSuccess(mutate("ari.json", value -> value.set("data", readTree(ARI_CROSS_ROWS))), MediaType.APPLICATION_JSON));

        SentinelFetch<SentinelPathogenRow> fetch = adapter.fetchPathogens(request, budget);

        assertThat(fetch.rows()).hasSize(24);
        assertThat(fetch.rows()).contains(
            new SentinelPathogenRow(2025, 52, "TOTAL", "계", "계", new BigDecimal("1395")),
            new SentinelPathogenRow(2026, 1, "TOTAL", "계", "계", new BigDecimal("1370")),
            new SentinelPathogenRow(2026, 1, "ND0001", "인플루엔자 바이러스", "인를루엔자", new BigDecimal("419")));
    }

    @Test
    @DisplayName("요청 사이 간격 — 마지막 호출 뒤 3초가 지나도록 기다린다. 이미 지난 만큼은 빼고, 다 지났으면 기다리지 않는다")
    void waitsRequestIntervalBetweenCalls() {
        expectScreen("ari");
        expectData("ari", ARI_FORM).andRespond(withSuccess(fixture("ari.json"), MediaType.APPLICATION_JSON));
        expectScreen("gstrnftn");
        expectData("gstrnftn", ARI_FORM).andRespond(withSuccess(fixture("gstrnftn.json"), MediaType.APPLICATION_JSON));
        expectScreen("influ");
        expectData("influ", INFLUENZA_FORM).andRespond(withSuccess(fixture("influ.json"), MediaType.APPLICATION_JSON));

        adapter.fetchPathogens(ARI, budget);
        clock.advance(Duration.ofSeconds(1));
        adapter.fetchPathogens(ENTERIC, budget);
        clock.advance(Duration.ofSeconds(5));
        adapter.fetchInfluenza(INFLUENZA, budget);

        server.verify();
        // ari: 화면(대기 없음) → 데이터(3s) / gstrnftn: 화면(1초 지남 → 2s) → 데이터(3s) / influ: 화면(5초 지남 → 대기 없음) → 데이터(3s)
        assertThat(sleeps).containsExactly(Duration.ofSeconds(3), Duration.ofSeconds(2), Duration.ofSeconds(3), Duration.ofSeconds(3));
        assertThat(budget.used()).isEqualTo(6);
    }

    @Test
    @DisplayName("실패한 호출 뒤에도 간격을 지킨다 — 포털이 끊은 직후 바로 다시 부르지 않는다")
    void waitsAfterFailedCall() {
        server.expect(requestTo(BASE_URL + "/ari.do")).andRespond(withException(new SocketTimeoutException("Read timed out")));
        expectScreen("gstrnftn");
        expectData("gstrnftn", ARI_FORM).andRespond(withSuccess(fixture("gstrnftn.json"), MediaType.APPLICATION_JSON));

        assertFails(() -> adapter.fetchPathogens(ARI, budget), SentinelImportErrorCode.PORTAL_CALL_FAILED);
        adapter.fetchPathogens(ENTERIC, budget);

        assertThat(sleeps).containsExactly(Duration.ofSeconds(3), Duration.ofSeconds(3));
    }

    @Test
    @DisplayName("화면 응답에 쿠키가 없으면 데이터를 부르지 않고 SESSION_MISSING 이다")
    void failsWhenScreenHasNoCookie() {
        server.expect(requestTo(BASE_URL + "/ari.do")).andRespond(withSuccess("<html></html>", MediaType.TEXT_HTML));

        assertFails(() -> adapter.fetchPathogens(ARI, budget), SentinelImportErrorCode.PORTAL_SESSION_MISSING, "operation=ari(2026-35~2026-39) screen");
        server.verify();
        assertThat(budget.used()).isEqualTo(1);
    }

    @Test
    @DisplayName("세션 쿠키(JSESSIONID) 없이 다른 쿠키만 오면 SESSION_MISSING 이다")
    void failsWhenSessionCookieMissing() {
        server.expect(requestTo(BASE_URL + "/ari.do"))
            .andRespond(withSuccess("<html></html>", MediaType.TEXT_HTML).header(HttpHeaders.SET_COOKIE, "clientid=" + CLIENT_ID + "; Path=/"));

        assertFails(() -> adapter.fetchPathogens(ARI, budget), SentinelImportErrorCode.PORTAL_SESSION_MISSING);
        server.verify();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("schemaChanges")
    @DisplayName("열 구성이 바뀌면 SCHEMA_CHANGED 다 — 열 순서로 붙인 코드가 다른 병원체로 가기 전에 멈춘다")
    void failsWhenPathogenSchemaChanges(String reason, Consumer<ObjectNode> change, String expectedMessage) {
        expectScreen("ari");
        expectData("ari", ARI_FORM).andRespond(withSuccess(mutate("ari.json", change), MediaType.APPLICATION_JSON));

        assertFails(() -> adapter.fetchPathogens(ARI, budget), SentinelImportErrorCode.SCHEMA_CHANGED, expectedMessage);
    }

    static Stream<Arguments> schemaChanges() {
        return Stream.of(
            // 원천 오타를 고친 응답도 형식 변경이다 — 한 글자라도 다르면 멈춘다.
            Arguments.of("오타가 고쳐짐", (Consumer<ObjectNode>) value -> replaceText(value.withArray("captionList"), 10, "인플루엔자 인플루엔자 바이러스"),
                "captionList[10] expected=인를루엔자 인플루엔자 바이러스 actual=인플루엔자 인플루엔자 바이러스"),
            Arguments.of("열 순서가 바뀜", (Consumer<ObjectNode>) value -> {
                ArrayNode captions = value.withArray("captionList");
                String second = captions.get(1).asText();
                replaceText(captions, 1, captions.get(2).asText());
                replaceText(captions, 2, second);
            }, "captionList[1] expected=세균 마이코플라즈마균 actual=세균 클라미디아균"),
            Arguments.of("열 하나 추가", (Consumer<ObjectNode>) value -> {
                value.withArray("captionList").add("바이러스 새 바이러스");
                value.withArray("headerList").addObject().put("TITLE", "바이러스").put("SUBTITLE", "새 바이러스");
                value.withArray("data").forEach(row -> ((ObjectNode) row).put("COLUMN13", "1"));
            }, "captionList[12] expected=null actual=바이러스 새 바이러스 size expected=12 actual=13"),
            Arguments.of("headerList 수가 다름", (Consumer<ObjectNode>) value -> value.withArray("headerList").remove(11),
                "headerList[11] expected=코로나19 코로나19 바이러스 actual=null size expected=12 actual=11"),
            // captionList 는 그대로인데 열의 정의(headerList)만 바뀌어도 열 대응을 믿을 수 없다.
            Arguments.of("headerList 순서만 바뀜", (Consumer<ObjectNode>) value -> {
                ArrayNode headers = value.withArray("headerList");
                JsonNode second = headers.get(1);
                headers.set(1, headers.get(2));
                headers.set(2, second);
            }, "headerList[1] expected=세균 마이코플라즈마균 actual=세균 클라미디아균"),
            Arguments.of("captionList 는 같고 headerList 만 다름",
                (Consumer<ObjectNode>) value -> ((ObjectNode) value.withArray("headerList").get(10)).put("TITLE", "인플루엔자"),
                "headerList[10] expected=인를루엔자 인플루엔자 바이러스 actual=인플루엔자 인플루엔자 바이러스"),
            Arguments.of("headerList 에 SUBTITLE 이 없음",
                (Consumer<ObjectNode>) value -> ((ObjectNode) value.withArray("headerList").get(3)).remove("SUBTITLE"), "SUBTITLE missing"),
            Arguments.of("행에만 열이 늘어남", (Consumer<ObjectNode>) value -> ((ObjectNode) value.withArray("data").get(0)).put("COLUMN13", "1"),
                "unexpected column COLUMN13 columns=12"),
            Arguments.of("열 번호가 0 부터", (Consumer<ObjectNode>) value -> ((ObjectNode) value.withArray("data").get(0)).put("COLUMN0", "1"),
                "unexpected column COLUMN0 columns=12"),
            Arguments.of("열 번호에 앞자리 0", (Consumer<ObjectNode>) value -> ((ObjectNode) value.withArray("data").get(1)).put("COLUMN01", "1"),
                "unexpected column COLUMN01 columns=12"),
            Arguments.of("행에 열이 빠짐", (Consumer<ObjectNode>) value -> ((ObjectNode) value.withArray("data").get(2)).remove("COLUMN12"),
                "COLUMN12 missing"),
            Arguments.of("captionList 없음", (Consumer<ObjectNode>) value -> value.remove("captionList"), "captionList is not an array"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("influenzaSchemaChanges")
    @DisplayName("인플루엔자 열 제목 · 연령대가 바뀌면 SCHEMA_CHANGED 다")
    void failsWhenInfluenzaSchemaChanges(String reason, Consumer<ObjectNode> change, String expectedMessage) {
        expectScreen("influ");
        expectData("influ", INFLUENZA_FORM).andRespond(withSuccess(mutate("influ.json", change), MediaType.APPLICATION_JSON));

        assertFails(() -> adapter.fetchInfluenza(INFLUENZA, budget), SentinelImportErrorCode.SCHEMA_CHANGED, expectedMessage);
    }

    static Stream<Arguments> influenzaSchemaChanges() {
        return Stream.of(
            Arguments.of("연령대 라벨이 바뀜", (Consumer<ObjectNode>) value -> ((ObjectNode) value.withArray("data").get(6)).put("TITLE", "65세이상"),
                "unexpected age label 65세이상"),
            Arguments.of("연령대가 겹침", (Consumer<ObjectNode>) value -> ((ObjectNode) value.withArray("data").get(6)).put("TITLE", "0세"),
                "duplicate age label 0세"),
            Arguments.of("연령대 행이 빠짐", (Consumer<ObjectNode>) value -> value.withArray("data").remove(0), "data rows expected=7 actual=6"),
            Arguments.of("연령 전체 행이 생김", (Consumer<ObjectNode>) value -> {
                ArrayNode data = value.withArray("data");
                data.set(6, ((ObjectNode) data.get(6).deepCopy()).put("TITLE", "전체"));
            }, "unexpected age label 전체"),
            Arguments.of("GR2 와 TITLE 이 어긋남", (Consumer<ObjectNode>) value -> {
                ((ObjectNode) value.withArray("headerList").get(1)).put("TITLE", "38주");
                replaceText(value.withArray("captionList"), 1, "38주");
            }, "GR2 37 does not match TITLE 38주"),
            Arguments.of("새해 열의 GR2 가 53 + 주차가 아님", (Consumer<ObjectNode>) value -> {
                ((ObjectNode) value.withArray("headerList").get(4)).put("GR2", "54").put("TITLE", "02주");
                replaceText(value.withArray("captionList"), 4, "02주");
            }, "GR2 54 does not match TITLE 02주"),
            Arguments.of("captionList 가 열 제목과 다름", (Consumer<ObjectNode>) value -> replaceText(value.withArray("captionList"), 0, "36 주"),
                "captionList[0] expected=36주 actual=36 주"),
            Arguments.of("행에 열이 빠짐", (Consumer<ObjectNode>) value -> ((ObjectNode) value.withArray("data").get(0)).remove("COLUMN5"), "COLUMN5 missing"),
            // 아직 열이 없는 절기는 headerList · captionList 가 둘 다 빈다. 한쪽만 비면 형식 변경이다.
            Arguments.of("headerList 만 비었음", (Consumer<ObjectNode>) value -> value.putArray("headerList"), "captionList[0] expected=null actual=36주"),
            Arguments.of("captionList 만 비었음", (Consumer<ObjectNode>) value -> value.putArray("captionList"), "captionList[0] expected=36주 actual=null"),
            Arguments.of("열이 없는데 행에 COLUMN 이 있음", (Consumer<ObjectNode>) value -> {
                value.putArray("headerList");
                value.putArray("captionList");
            }, "unexpected column COLUMN"));
    }

    @Test
    @DisplayName("아직 시작하지 않은 절기 — headerList · captionList 가 비고 행에 COLUMN 이 없으면 0행 정상이다 (절기 첫 주 실행)")
    void readsSeasonWithoutColumnsYet() {
        expectScreen("influ");
        expectData("influ", "startYear=2027&endYear=2028&age=&intoDivi=1&sido=").andRespond(withSuccess(INFLU_FUTURE, MediaType.APPLICATION_JSON));

        SentinelFetch<SentinelIliRow> fetch = adapter.fetchInfluenza(SentinelRequest.season(2027), budget);

        assertThat(fetch.rows()).isEmpty();
        assertThat(fetch.rawRowCount()).isEqualTo(7);
        assertThat(fetch.pendingCount()).isZero();
        assertThat(fetch.contentSha256()).isEqualTo(sha256(INFLU_FUTURE.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("열이 없는 절기도 연령대 라벨은 검사한다")
    void checksAgeLabelsEvenWithoutColumns() {
        expectScreen("influ");
        expectData("influ", "startYear=2027&endYear=2028&age=&intoDivi=1&sido=")
            .andRespond(withSuccess(INFLU_FUTURE.replace("65세 이상", "65세+"), MediaType.APPLICATION_JSON));

        assertFails(() -> adapter.fetchInfluenza(SentinelRequest.season(2027), budget), SentinelImportErrorCode.SCHEMA_CHANGED, "unexpected age label 65세+");
    }

    @Test
    @DisplayName("요청 범위 밖의 주가 오면 RESPONSE_INVALID 다")
    void failsWhenWeekOutOfRequestedRange() {
        SentinelRequest request = SentinelRequest.weeks(SentinelProgram.ARI, new KdcaWeek(2026, 36), new KdcaWeek(2026, 39));
        expectScreen("ari");
        expectData("ari", "startYear=2026&startWeek=36&endYear=2026&endWeek=39&dayCheck=1&infectiousGubun=&subInfectious=&age=")
            .andRespond(withSuccess(fixture("ari.json"), MediaType.APPLICATION_JSON));

        assertFails(() -> adapter.fetchPathogens(request, budget), SentinelImportErrorCode.RESPONSE_INVALID, "week out of range 2026-35");
    }

    @Test
    @DisplayName("공표가 끝난 주 · 끝났지만 미공표인 주(모든 칸 집계 중) · 진행 중인 주(행 없음)가 섞인 실제 범위를 읽는다")
    void readsRangeEndingWithUnpublishedWeeks() {
        SentinelRequest request = SentinelRequest.weeks(SentinelProgram.ARI, new KdcaWeek(2026, 37), new KdcaWeek(2026, 41));
        expectScreen("ari");
        expectData("ari", "startYear=2026&startWeek=37&endYear=2026&endWeek=41&dayCheck=1&infectiousGubun=&subInfectious=&age=")
            .andRespond(withSuccess(mutate("ari.json", value -> value.set("data", readTree(ARI_CURRENT_ROWS))), MediaType.APPLICATION_JSON));

        SentinelFetch<SentinelPathogenRow> fetch = adapter.fetchPathogens(request, budget);

        assertThat(fetch.rows()).hasSize(36);
        assertThat(fetch.rawRowCount()).isEqualTo(4);
        // 40주의 12칸은 아직 없는 값이라 결측(null)으로 덮어쓰지 않는다.
        assertThat(fetch.pendingCount()).isEqualTo(12);
        assertThat(fetch.nullValueCount()).isZero();
        assertThat(fetch.rows()).extracting(SentinelPathogenRow::week).containsOnly(37, 38, 39);
        assertThat(fetch.rows()).contains(new SentinelPathogenRow(2026, 39, "TOTAL", "계", "계", new BigDecimal("1162")));
    }

    @Test
    @DisplayName("두 주 이상을 요청했는데 data 가 비면 RESPONSE_INVALID 다 — 최근 범위에는 공표된 주가 있어야 한다")
    void failsWhenDataEmptyForMultiWeekRange() {
        expectScreen("ari");
        expectData("ari", ARI_FORM).andRespond(withSuccess(mutate("ari.json", value -> value.putArray("data")), MediaType.APPLICATION_JSON));

        assertFails(() -> adapter.fetchPathogens(ARI, budget), SentinelImportErrorCode.RESPONSE_INVALID, "data is empty");
    }

    @Test
    @DisplayName("진행 중인 주 하나만 요청하면 data 가 비어도 0행 정상이다")
    void acceptsEmptyDataForSingleWeek() {
        SentinelRequest request = SentinelRequest.weeks(SentinelProgram.ARI, new KdcaWeek(2026, 41), new KdcaWeek(2026, 41));
        expectScreen("ari");
        expectData("ari", "startYear=2026&startWeek=41&endYear=2026&endWeek=41&dayCheck=1&infectiousGubun=&subInfectious=&age=")
            .andRespond(withSuccess(mutate("ari.json", value -> value.putArray("data")), MediaType.APPLICATION_JSON));

        assertThat(adapter.fetchPathogens(request, budget).rows()).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidPathogenRows")
    @DisplayName("행 · 주차가 어긋나면 RESPONSE_INVALID 다")
    void failsWhenPathogenRowInvalid(String reason, Consumer<ObjectNode> change, String expectedMessage) {
        expectScreen("ari");
        expectData("ari", ARI_FORM).andRespond(withSuccess(mutate("ari.json", change), MediaType.APPLICATION_JSON));

        assertFails(() -> adapter.fetchPathogens(ARI, budget), SentinelImportErrorCode.RESPONSE_INVALID, expectedMessage);
    }

    static Stream<Arguments> invalidPathogenRows() {
        return Stream.of(
            Arguments.of("같은 주가 두 번", (Consumer<ObjectNode>) value -> ((ObjectNode) value.withArray("data").get(1)).put("SUBTITLE", "35"),
                "duplicate week 2026-35"),
            Arguments.of("주차가 한 자리", (Consumer<ObjectNode>) value -> ((ObjectNode) value.withArray("data").get(0)).put("SUBTITLE", "5"),
                "unexpected week 2026-5"),
            Arguments.of("53주가 없는 해의 53주", (Consumer<ObjectNode>) value -> ((ObjectNode) value.withArray("data").get(0)).put("SUBTITLE", "53"),
                "unexpected week 2026-53"),
            Arguments.of("연도가 없음", (Consumer<ObjectNode>) value -> ((ObjectNode) value.withArray("data").get(0)).put("TITLE", ""), "TITLE missing"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidInfluenzaWeeks")
    @DisplayName("인플루엔자 열의 주가 절기 밖이거나 없는 주 · 겹치는 주면 RESPONSE_INVALID 다 — 연도를 잘못 붙이지 않는다")
    void failsWhenInfluenzaWeekInvalid(String reason, Consumer<ObjectNode> change, String expectedMessage) {
        expectScreen("influ");
        expectData("influ", INFLUENZA_FORM).andRespond(withSuccess(mutate("influ.json", change), MediaType.APPLICATION_JSON));

        assertFails(() -> adapter.fetchInfluenza(INFLUENZA, budget), SentinelImportErrorCode.RESPONSE_INVALID, expectedMessage);
    }

    static Stream<Arguments> invalidInfluenzaWeeks() {
        return Stream.of(
            Arguments.of("시작 연도인데 35주 이하", (Consumer<ObjectNode>) value -> renameHeader(value, 4, "10", "10주"), "week out of season 2026-10"),
            Arguments.of("끝 연도인데 36주 이상", (Consumer<ObjectNode>) value -> renameHeader(value, 4, "89", "36주"), "week out of season 2027-36"),
            Arguments.of("53주가 없는 해의 53주", (Consumer<ObjectNode>) value -> renameHeader(value, 4, "53", "53주"), "unexpected week 2026-53"),
            Arguments.of("같은 주가 두 번", (Consumer<ObjectNode>) value -> renameHeader(value, 4, "39", "39주"), "duplicate week 2026-39"));
    }

    @Test
    @DisplayName("값 — 쉼표를 지우고, '-' · 빈 값 · 음수는 결측(null)으로, 집계 중은 행 없이 센다")
    void readsMissingAndPendingValues() {
        expectScreen("ari");
        expectData("ari", ARI_FORM).andRespond(withSuccess(mutate("ari.json", value -> {
            ObjectNode row = (ObjectNode) value.withArray("data").get(4);
            row.put("COLUMN2", "-");
            row.put("COLUMN3", "");
            row.put("COLUMN4", "-3");
            row.put("COLUMN5", "집계 중");
            row.put("COLUMN6", " 1,234 ");
        }), MediaType.APPLICATION_JSON));

        SentinelFetch<SentinelPathogenRow> fetch = adapter.fetchPathogens(ARI, budget);

        assertThat(fetch.rows()).hasSize(59);
        assertThat(fetch.nullValueCount()).isEqualTo(3);
        assertThat(fetch.pendingCount()).isEqualTo(1);
        assertThat(fetch.rows()).filteredOn(row -> row.week() == 39 && row.value() == null)
            .extracting(SentinelPathogenRow::diseaseKey).containsExactly("ND0708", "ND0709", "ND0701");
        assertThat(fetch.rows()).filteredOn(row -> row.week() == 39).extracting(SentinelPathogenRow::diseaseKey).doesNotContain("ND0702");
        assertThat(fetch.rows()).contains(new SentinelPathogenRow(2026, 39, "ND0703", "파라인플루엔자바이러스", "바이러스", new BigDecimal("1234")));
    }

    @ParameterizedTest(name = "value={0}")
    @ValueSource(strings = {"abc", "1e5", "+5", "1.2.3", "0.125", "12,34", "1,,5", ",5", "1,5", "1234,567", "1,520,"})
    @DisplayName("숫자로 읽을 수 없거나 DECIMAL(12,2) 에 맞지 않는 값은 RESPONSE_INVALID 다 — 조용히 0 이나 null 로 바꾸지 않는다")
    void failsOnUnexpectedValue(String raw) {
        expectScreen("ari");
        expectData("ari", ARI_FORM).andRespond(withSuccess(mutate("ari.json",
            value -> ((ObjectNode) value.withArray("data").get(0)).put("COLUMN2", raw)), MediaType.APPLICATION_JSON));

        assertFails(() -> adapter.fetchPathogens(ARI, budget), SentinelImportErrorCode.RESPONSE_INVALID, "operation=ari(2026-35~2026-39)");
    }

    @Test
    @DisplayName("화면 요청이 500 이면 데이터를 부르지 않고 PORTAL_HTTP_ERROR 다 — 본문 · URL 은 싣지 않는다")
    void httpErrorOnScreen() {
        server.expect(requestTo(BASE_URL + "/ari.do"))
            .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).contentType(MediaType.TEXT_HTML).body("<html>서버 오류 내부 정보</html>"));

        SentinelImportException exception = assertFails(() -> adapter.fetchPathogens(ARI, budget), SentinelImportErrorCode.PORTAL_HTTP_ERROR,
            "operation=ari(2026-35~2026-39) screen", "httpStatus=500");
        assertThat(exception.getMessage()).doesNotContain("내부 정보");
        server.verify();
        assertThat(budget.used()).isEqualTo(1);
    }

    @Test
    @DisplayName("데이터 요청이 503 이면 PORTAL_HTTP_ERROR 다")
    void httpErrorOnData() {
        expectScreen("influ");
        expectData("influ", INFLUENZA_FORM).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertFails(() -> adapter.fetchInfluenza(INFLUENZA, budget), SentinelImportErrorCode.PORTAL_HTTP_ERROR,
            "operation=influ(2026-2027) data", "httpStatus=503");
    }

    @Test
    @DisplayName("리다이렉트(3xx)는 따라가지 않고 PORTAL_HTTP_ERROR 다")
    void redirectIsHttpError() {
        server.expect(requestTo(BASE_URL + "/ari.do"))
            .andRespond(withStatus(HttpStatus.FOUND).header(HttpHeaders.LOCATION, "https://portal.test/maintenance.html"));

        assertFails(() -> adapter.fetchPathogens(ARI, budget), SentinelImportErrorCode.PORTAL_HTTP_ERROR, "httpStatus=302");
    }

    @Test
    @DisplayName("데이터 요청에 200 으로 HTML(포털 점검 화면)이 오면 RESPONSE_INVALID 다")
    void htmlOkIsResponseInvalid() {
        expectScreen("ari");
        expectData("ari", ARI_FORM).andRespond(withSuccess("<!DOCTYPE html><html><body>시스템 점검 중</body></html>", MediaType.TEXT_HTML));

        SentinelImportException exception = assertFails(() -> adapter.fetchPathogens(ARI, budget), SentinelImportErrorCode.RESPONSE_INVALID, "not json");
        assertThat(exception.getMessage()).doesNotContain("점검");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidEnvelopes")
    @DisplayName("봉투가 어긋나면 RESPONSE_INVALID 다")
    void failsOnInvalidEnvelope(String body, String expectedMessage) {
        expectScreen("ari");
        expectData("ari", ARI_FORM).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        assertFails(() -> adapter.fetchPathogens(ARI, budget), SentinelImportErrorCode.RESPONSE_INVALID, expectedMessage);
    }

    static Stream<Arguments> invalidEnvelopes() {
        return Stream.of(
            Arguments.of("{\"result\":false,\"value\":null,\"message\":\"error\"}", "result is false"),
            Arguments.of("{\"value\":{}}", "result missing"),
            Arguments.of("{\"result\":true,\"value\":null}", "value missing"),
            Arguments.of("[]", "not json object"),
            Arguments.of("{\"result\":", "not json"));
    }

    @Test
    @DisplayName("I/O · timeout 은 PORTAL_CALL_FAILED 다 — 가장 안쪽 원인만 붙이고 URL 을 담은 예외는 붙이지 않는다")
    void callFailedOnTimeout() {
        expectScreen("ari");
        server.expect(requestTo(BASE_URL + "/ariListAjax.do")).andRespond(withException(new SocketTimeoutException("Read timed out")));

        SentinelImportException exception = assertFails(() -> adapter.fetchPathogens(ARI, budget), SentinelImportErrorCode.PORTAL_CALL_FAILED,
            "operation=ari(2026-35~2026-39) data", "reason=SocketTimeoutException");
        assertThat(exception.getCause()).isInstanceOf(SocketTimeoutException.class);
        assertThat(budget.used()).isEqualTo(2);
    }

    @Test
    @DisplayName("호출 상한이 1 이면 화면만 부르고 데이터 요청 전에 REQUEST_BUDGET_EXCEEDED 다")
    void stopsBeforeDataWhenBudgetExhausted() {
        budget = new SentinelCallBudget(1);
        expectScreen("ari");

        assertFails(() -> adapter.fetchPathogens(ARI, budget), SentinelImportErrorCode.REQUEST_BUDGET_EXCEEDED, "used=1", "maxCallsPerRun=1");
        server.verify();
        assertThat(budget.used()).isEqualTo(1);
    }

    @Test
    @DisplayName("상한을 다 쓴 예산으로는 아무 요청도 보내지 않는다")
    void sendsNothingWithExhaustedBudget() {
        budget = new SentinelCallBudget(2);
        expectScreen("ari");
        expectData("ari", ARI_FORM).andRespond(withSuccess(fixture("ari.json"), MediaType.APPLICATION_JSON));
        adapter.fetchPathogens(ARI, budget);

        assertFails(() -> adapter.fetchInfluenza(INFLUENZA, budget), SentinelImportErrorCode.REQUEST_BUDGET_EXCEEDED, "used=2", "maxCallsPerRun=2");
        server.verify();
    }

    @Test
    @DisplayName("프로그램이 메서드와 맞지 않으면 호출하지 않고 IllegalArgumentException 이다")
    void rejectsMismatchedProgramBeforeAnyCall() {
        assertThatThrownBy(() -> adapter.fetchPathogens(INFLUENZA, budget)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adapter.fetchInfluenza(ARI, budget)).isInstanceOf(IllegalArgumentException.class);
        server.verify();
        assertThat(budget.used()).isZero();
    }

    /** 화면 응답 — 세션 · 클라이언트 쿠키를 속성과 함께 준다. 화면 요청에는 쿠키 · 폼이 없다. */
    private void expectScreen(String icdNm) {
        server.expect(requestTo(BASE_URL + "/" + icdNm + ".do"))
            .andExpect(method(HttpMethod.GET))
            .andExpect(headerDoesNotExist(HttpHeaders.COOKIE))
            .andRespond(withSuccess("<html><body>화면</body></html>", MediaType.TEXT_HTML).header(HttpHeaders.SET_COOKIE,
                "JSESSIONID=" + SESSION_ID + "; Path=/; Secure; HttpOnly", "clientid=" + CLIENT_ID + "; Path=/; Max-Age=86400"));
    }

    /** 데이터 요청 — 화면 쿠키(이름 = 값만), XHR 헤더, UTF-8 폼을 싣는다. */
    private ResponseActions expectData(String icdNm, String form) {
        return server.expect(requestTo(BASE_URL + "/" + icdNm + "ListAjax.do"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("X-Requested-With", "XMLHttpRequest"))
            .andExpect(header(HttpHeaders.COOKIE, COOKIE))
            .andExpect(content().contentType(FORM_UTF8))
            .andExpect(content().string(form));
    }

    /** 예외 코드 · 메시지를 확인하고, 메시지 · cause 체인 어디에도 쿠키 값과 요청 URL 이 없음을 단언한다. */
    private static SentinelImportException assertFails(Supplier<?> call, SentinelImportErrorCode expected, String... messageParts) {
        SentinelImportException[] thrown = new SentinelImportException[1];
        assertThatThrownBy(call::get)
            .isInstanceOfSatisfying(SentinelImportException.class, exception -> {
                assertThat(exception.getErrorCode()).isEqualTo(expected);
                assertThat(exception.getMessage()).startsWith("[" + expected.getCode() + "]");
                if (messageParts.length > 0) {
                    assertThat(exception.getMessage()).contains(messageParts);
                }
                thrown[0] = exception;
            });
        for (Throwable current = thrown[0]; current != null; current = current.getCause()) {
            assertThat(String.valueOf(current.getMessage())).as(current.getClass().getName())
                .doesNotContain(SESSION_ID).doesNotContain(CLIENT_ID).doesNotContain("portal.test");
        }
        return thrown[0];
    }

    private static SentinelPortalProperties properties() {
        return new SentinelPortalProperties(BASE_URL, Duration.ofSeconds(3), Duration.ofSeconds(30), Duration.ofSeconds(3),
            SentinelPortalProperties.DEFAULT_USER_AGENT);
    }

    private static byte[] fixture(String name) {
        try (InputStream input = SentinelPortalSourceAdapterTest.class.getResourceAsStream("/sentinelimport/" + name)) {
            if (input == null) {
                throw new IllegalStateException("fixture not found: " + name);
            }
            return input.readAllBytes();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** 원본 응답의 {@code value} 를 고쳐 다시 직렬화한다. 고치지 않은 값은 원본 그대로다. */
    private static String mutate(String name, Consumer<ObjectNode> change) {
        ObjectNode root = (ObjectNode) readTree(new String(fixture(name), StandardCharsets.UTF_8));
        change.accept((ObjectNode) root.get("value"));
        return root.toString();
    }

    private static JsonNode readTree(String json) {
        try {
            return OBJECT_MAPPER.readTree(json);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static void replaceText(ArrayNode array, int index, String text) {
        array.set(index, array.textNode(text));
    }

    /** 인플루엔자 열 제목 하나를 바꾼다 — {@code headerList} 와 {@code captionList} 를 함께 바꿔 열 제목 대조는 통과시킨다. */
    private static void renameHeader(ObjectNode value, int index, String gr2, String title) {
        ((ObjectNode) value.withArray("headerList").get(index)).put("GR2", gr2).put("TITLE", title);
        replaceText(value.withArray("captionList"), index, title);
    }

    private static String sha256(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    /** 대기할 때만 흐르는 시계. 테스트가 직접 {@link #advance} 해서 호출 사이에 지난 시간을 흉내 낸다. */
    private static final class FakeClock extends Clock {

        private Instant now;

        private FakeClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
