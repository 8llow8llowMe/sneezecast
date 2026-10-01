package com.sneezecast.domainlayer.districtimport.adapter.out.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.sneezecast.domainlayer.districtimport.application.exception.DistrictImportErrorCode;
import com.sneezecast.domainlayer.districtimport.application.exception.DistrictImportException;
import com.sneezecast.domainlayer.districtimport.domain.model.DistrictSnapshot;
import com.sneezecast.domainlayer.districtimport.domain.model.ImportedDistrict;
import com.sneezecast.global.properties.SgisProperties;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 응답 본문은 data-api-analysis §1-1 의 2026-09-30 실호출 형식을 줄여 옮겼다 — 봉투({@code errCd} · {@code errMsg} · {@code id} · {@code trId}),
 * 인증 {@code result{accessToken, accessTimeout(epoch 밀리초)}}, 단계별 주소 {@code result[]{cd, addr_name, full_addr, x_coor, y_coor}},
 * 경계 {@code features[]{properties{adm_cd, adm_nm(전체 주소), x, y, addr_en}, geometry}}. 실제 SGIS 는 부르지 않는다.
 */
class SgisDistrictSourceAdapterTest {

    private static final String BASE_URL = "https://sgis.test/OpenAPI3";
    private static final String CONSUMER_KEY = "test-consumer-key";
    private static final String CONSUMER_SECRET = "test-consumer-secret-0123456789";
    private static final int YEAR = 2025;

    private static final String SIDO_STAGE = envelope("API_0701", """
        "result": [
          {"y_coor": "1952053", "full_addr": "서울특별시", "x_coor": "953932", "addr_name": "서울특별시", "cd": "11"},
          {"y_coor": "1919582", "full_addr": "경기도", "x_coor": "975564", "addr_name": "경기도", "cd": "31"}
        ]""");
    private static final String SEOUL_SIGUNGU_STAGE = envelope("API_0701", """
        "result": [
          {"y_coor": "1945032", "full_addr": "서울특별시 송파구", "x_coor": "968612", "addr_name": "송파구", "cd": "11240"}
        ]""");
    /** 수원시 장안구(31011)가 단계별 주소에 없는 경우 — adm_nm 의 가운데 토큰으로 채운다. */
    private static final String GYEONGGI_SIGUNGU_STAGE = envelope("API_0701", """
        "result": [
          {"y_coor": "1951234", "full_addr": "경기도 성남시 분당구", "x_coor": "961234", "addr_name": "성남시 분당구", "cd": "31023"}
        ]""");
    private static final String SEOUL_HADMAREA = hadmarea("""
        {"type": "Feature", "geometry": {"type": "Polygon", "coordinates": [[[968000.1, 1944000.2], [968100.3, 1944100.4], [968000.1, 1944000.2]]]},
         "properties": {"adm_nm": "서울특별시 송파구 가락1동", "adm_cd": "11240660", "x": "968312", "y": "1943880", "addr_en": "Garak 1(il)-dong, Songpa-gu, Seoul"}},
        {"type": "Feature", "geometry": {"type": "MultiPolygon", "coordinates": [[[[968200.1, 1944200.2], [968300.3, 1944300.4], [968200.1, 1944200.2]]]]},
         "properties": {"adm_nm": "서울특별시 송파구 풍납1동", "adm_cd": "11240510", "x": "970112", "y": "1949880"}}""");
    private static final String GYEONGGI_HADMAREA = hadmarea("""
        {"type": "Feature", "geometry": {"type": "Polygon", "coordinates": [[[955000.1, 1924000.2], [955100.3, 1924100.4], [955000.1, 1924000.2]]]},
         "properties": {"adm_nm": "경기도 수원시 장안구 파장동", "adm_cd": "31011510", "x": "955312", "y": "1924880"}},
        {"type": "Feature", "geometry": {"type": "Polygon", "coordinates": [[[961000.1, 1951000.2], [961100.3, 1951100.4], [961000.1, 1951000.2]]]},
         "properties": {"adm_nm": "경기도 성남시 분당구 정자1동", "adm_cd": "31023640", "x": "961312", "y": "1951880"}}""");
    private static final String TOKEN_INVALID = """
        {"id": "API_0604", "errMsg": "인증 정보가 존재하지 않습니다", "errCd": -401, "trId": "tr-x"}""";

    private MockRestServiceServer server;
    private SgisDistrictSourceAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = adapterWith(new SgisProperties(BASE_URL, CONSUMER_KEY, CONSUMER_SECRET, Duration.ofSeconds(3), Duration.ofSeconds(30)));
    }

    @Test
    @DisplayName("인증 → 시도 목록 → 시도마다 시군구 · 경계로 전국 스냅샷을 만든다 — 이름은 adm_nm 마지막 토큰, 시군구는 단계별 주소로 맞춘다")
    void buildsSnapshotFromStageAndBoundary() {
        expectAuth("token-1");
        expectSidoStage("token-1", SIDO_STAGE);
        expectSigunguStage("token-1", "11", SEOUL_SIGUNGU_STAGE);
        expectHadmarea("token-1", "11", SEOUL_HADMAREA);
        expectSigunguStage("token-1", "31", GYEONGGI_SIGUNGU_STAGE);
        expectHadmarea("token-1", "31", GYEONGGI_HADMAREA);

        DistrictSnapshot snapshot = adapter.fetchSnapshot(YEAR);

        server.verify();
        assertThat(snapshot.year()).isEqualTo(YEAR);
        assertThat(snapshot.sidoCodes()).containsExactly("11", "31");
        assertThat(snapshot.districts()).containsExactly(
            new ImportedDistrict("11240660", "가락1동", "11", "서울특별시", "11240", "송파구"),
            new ImportedDistrict("11240510", "풍납1동", "11", "서울특별시", "11240", "송파구"),
            // 단계별 주소에 없는 시군구 → adm_nm 의 시도 다음 ~ 마지막 앞 토큰
            new ImportedDistrict("31011510", "파장동", "31", "경기도", "31011", "수원시 장안구"),
            new ImportedDistrict("31023640", "정자1동", "31", "경기도", "31023", "성남시 분당구"));
    }

    @Test
    @DisplayName("errCd -401(토큰 만료)이면 토큰을 다시 받아 그 요청만 한 번 재시도한다")
    void reauthenticatesOnceOnTokenInvalid() {
        expectAuth("token-1");
        expectSidoStage("token-1", SIDO_STAGE);
        expectSigunguStage("token-1", "11", SEOUL_SIGUNGU_STAGE);
        expectHadmarea("token-1", "11", TOKEN_INVALID);
        expectAuth("token-2");
        expectHadmarea("token-2", "11", SEOUL_HADMAREA);
        // 다시 받은 토큰을 이후 요청에도 쓴다.
        expectSigunguStage("token-2", "31", GYEONGGI_SIGUNGU_STAGE);
        expectHadmarea("token-2", "31", GYEONGGI_HADMAREA);

        DistrictSnapshot snapshot = adapter.fetchSnapshot(YEAR);

        server.verify();
        assertThat(snapshot.districts()).hasSize(4);
    }

    @Test
    @DisplayName("다시 받은 토큰도 -401 이면 더 재시도하지 않고 SGIS_TOKEN_REJECTED 로 실패한다")
    void failsWhenTokenRejectedTwice() {
        expectAuth("token-1");
        expectSidoStage("token-1", SIDO_STAGE);
        expectSigunguStage("token-1", "11", TOKEN_INVALID);
        expectAuth("token-2");
        expectSigunguStage("token-2", "11", TOKEN_INVALID);

        assertFails(DistrictImportErrorCode.SGIS_TOKEN_REJECTED, "stage(cd=11)");
        server.verify();
    }

    @Test
    @DisplayName("HTTP 200 이어도 errCd -200(경계 연도 범위 밖)이면 SGIS_API_ERROR 로 실패한다")
    void failsOnErrCdEvenWithHttp200() {
        expectAuth("token-1");
        expectSidoStage("token-1", SIDO_STAGE);
        expectSigunguStage("token-1", "11", SEOUL_SIGUNGU_STAGE);
        expectHadmarea("token-1", "11", """
            {"type": "FeatureCollection", "features": [], "errMsg": "경계데이터 년도 정보를 확인해주세요", "errCd": -200, "id": "API_0604", "trId": "tr-x"}""");

        DistrictImportException exception = assertFails(DistrictImportErrorCode.SGIS_API_ERROR, "errCd=-200");
        assertThat(exception.getMessage()).contains("hadmarea(adm_cd=11)", "경계데이터 년도 정보를 확인해주세요");
        server.verify();
    }

    @Test
    @DisplayName("errCd 가 없는 응답은 성공으로 보지 않는다")
    void failsWhenErrCdMissing() {
        expectAuth("token-1");
        server.expect(requestTo(startsWith(BASE_URL + SgisDistrictSourceAdapter.STAGE_PATH)))
            .andRespond(withSuccess("""
                {"result": [{"cd": "11", "addr_name": "서울특별시"}]}""", MediaType.APPLICATION_JSON));

        assertFails(DistrictImportErrorCode.SGIS_RESPONSE_INVALID, "errCd missing");
    }

    @Test
    @DisplayName("HTTP 200 에 JSON 이 아닌 본문이면 SGIS_RESPONSE_INVALID 다")
    void failsOnNonJsonBody() {
        expectAuth("token-1");
        server.expect(requestTo(startsWith(BASE_URL + SgisDistrictSourceAdapter.STAGE_PATH)))
            .andRespond(withSuccess("<html>점검 중</html>", MediaType.TEXT_HTML));

        assertFails(DistrictImportErrorCode.SGIS_RESPONSE_INVALID, "operation=stage");
    }

    @Test
    @DisplayName("5xx 는 SGIS_HTTP_ERROR 다")
    void failsOnServerError() {
        expectAuth("token-1");
        server.expect(requestTo(startsWith(BASE_URL + SgisDistrictSourceAdapter.STAGE_PATH))).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertFails(DistrictImportErrorCode.SGIS_HTTP_ERROR, "httpStatus=503");
    }

    @Test
    @DisplayName("인증 파라미터 누락(412 + HTML)은 SGIS_AUTH_FAILED 다")
    void failsOnAuth412() {
        server.expect(requestTo(startsWith(BASE_URL + SgisDistrictSourceAdapter.AUTH_PATH)))
            .andRespond(withStatus(HttpStatus.PRECONDITION_FAILED).contentType(MediaType.TEXT_HTML).body("<html><body>필수 파라미터 누락</body></html>"));

        DistrictImportException exception = assertFails(DistrictImportErrorCode.SGIS_AUTH_FAILED, "httpStatus=412");
        assertThat(exception.getMessage()).doesNotContain(CONSUMER_SECRET).doesNotContain(CONSUMER_KEY);
    }

    @Test
    @DisplayName("틀린 인증 정보(HTTP 200 + errCd -401)는 재시도하지 않고 SGIS_AUTH_FAILED 다 — 메시지 · 원인 어디에도 인증키가 없다")
    void failsOnWrongCredentialsWithoutLeakingSecret() {
        server.expect(requestTo(startsWith(BASE_URL + SgisDistrictSourceAdapter.AUTH_PATH)))
            .andRespond(withSuccess("""
                {"id": "API_0101", "errMsg": "인증정보가 존재하지 않습니다", "errCd": -401, "trId": "tr-x"}""", MediaType.APPLICATION_JSON));

        DistrictImportException exception = assertFails(DistrictImportErrorCode.SGIS_AUTH_FAILED, "errCd=-401");
        server.verify();
        for (Throwable current = exception; current != null; current = current.getCause()) {
            assertThat(String.valueOf(current.getMessage())).doesNotContain(CONSUMER_SECRET).doesNotContain(CONSUMER_KEY);
        }
    }

    @Test
    @DisplayName("인증키가 비어 있으면 아무 요청도 보내지 않고 SGIS_CREDENTIALS_MISSING 이다")
    void failsWithoutCredentialsBeforeAnyCall() {
        adapter = adapterWith(new SgisProperties(BASE_URL, "", " ", Duration.ofSeconds(3), Duration.ofSeconds(30)));

        assertFails(DistrictImportErrorCode.SGIS_CREDENTIALS_MISSING, "SGIS_CONSUMER_KEY");
        server.verify();

        adapter = adapterWith(new SgisProperties(BASE_URL, CONSUMER_KEY, null, Duration.ofSeconds(3), Duration.ofSeconds(30)));
        assertFails(DistrictImportErrorCode.SGIS_CREDENTIALS_MISSING, "SGIS_CONSUMER_SECRET");
    }

    @Test
    @DisplayName("설정 toString 은 인증키 값을 가린다")
    void propertiesToStringMasksCredentials() {
        String text = new SgisProperties(BASE_URL, CONSUMER_KEY, CONSUMER_SECRET, Duration.ofSeconds(3), Duration.ofSeconds(30)).toString();

        assertThat(text).doesNotContain(CONSUMER_KEY).doesNotContain(CONSUMER_SECRET).contains("consumerKey=****", "consumerSecret=****", BASE_URL);
    }

    @Test
    @DisplayName("인증 요청의 I/O · timeout 은 SGIS_CALL_FAILED 다 — 메시지 · cause 체인 어디에도 인증키가 없다 (URL 을 담은 ResourceAccessException 을 붙이지 않는다)")
    void authTimeoutIsCallFailedWithoutLeakingSecret() {
        server.expect(requestTo(startsWith(BASE_URL + SgisDistrictSourceAdapter.AUTH_PATH))).andRespond(withException(new SocketTimeoutException("Read timed out")));

        DistrictImportException exception = assertFails(DistrictImportErrorCode.SGIS_CALL_FAILED, "operation=auth");
        assertThat(exception.getMessage()).contains("reason=SocketTimeoutException");
        assertNoSecretInChain(exception, CONSUMER_SECRET, CONSUMER_KEY);
        assertThat(exception.getCause()).isInstanceOf(SocketTimeoutException.class);
    }

    @Test
    @DisplayName("토큰을 실은 요청의 I/O 오류도 SGIS_CALL_FAILED 다 — 메시지 · cause 체인 어디에도 accessToken 이 없다")
    void stageIoErrorIsCallFailedWithoutLeakingToken() {
        expectAuth("secret-access-token-1");
        server.expect(requestTo(startsWith(BASE_URL + SgisDistrictSourceAdapter.STAGE_PATH))).andRespond(withException(new ConnectException("Connection refused")));

        DistrictImportException exception = assertFails(DistrictImportErrorCode.SGIS_CALL_FAILED, "operation=stage");
        assertNoSecretInChain(exception, "secret-access-token-1", CONSUMER_SECRET);
    }

    @Test
    @DisplayName("인증키의 + / = 는 쿼리에서 %2B %2F %3D 로 인코딩된다 — '+' 가 공백으로 읽혀 인증이 깨지지 않게")
    void encodesReservedCharactersInCredentials() {
        adapter = adapterWith(new SgisProperties(BASE_URL, "key+/=", "secret+/=", Duration.ofSeconds(3), Duration.ofSeconds(30)));
        server.expect(requestTo(startsWith(BASE_URL + SgisDistrictSourceAdapter.AUTH_PATH)))
            .andExpect(request -> assertThat(request.getURI().getRawQuery()).isEqualTo("consumer_key=key%2B%2F%3D&consumer_secret=secret%2B%2F%3D"))
            .andRespond(withSuccess("""
                {"id": "API_0101", "errMsg": "인증정보가 존재하지 않습니다", "errCd": -401, "trId": "tr-x"}""", MediaType.APPLICATION_JSON));

        assertFails(DistrictImportErrorCode.SGIS_AUTH_FAILED, "errCd=-401");
        server.verify();
    }

    private static void assertNoSecretInChain(Throwable exception, String... secrets) {
        for (Throwable current = exception; current != null; current = current.getCause()) {
            for (String secret : secrets) {
                assertThat(String.valueOf(current.getMessage())).as(current.getClass().getName()).doesNotContain(secret);
                assertThat(current.toString()).doesNotContain(secret);
            }
        }
    }

    private SgisDistrictSourceAdapter adapterWith(SgisProperties properties) {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        return new SgisDistrictSourceAdapter(builder.build(), properties);
    }

    private DistrictImportException assertFails(DistrictImportErrorCode expected, String messagePart) {
        DistrictImportException[] thrown = new DistrictImportException[1];
        assertThatThrownBy(() -> adapter.fetchSnapshot(YEAR))
            .isInstanceOfSatisfying(DistrictImportException.class, exception -> {
                assertThat(exception.getErrorCode()).isEqualTo(expected);
                assertThat(exception.getMessage()).contains(messagePart);
                thrown[0] = exception;
            });
        return thrown[0];
    }

    private void expectAuth(String token) {
        server.expect(requestTo(startsWith(BASE_URL + SgisDistrictSourceAdapter.AUTH_PATH)))
            .andExpect(method(HttpMethod.GET))
            .andExpect(queryParam("consumer_key", CONSUMER_KEY))
            .andExpect(queryParam("consumer_secret", CONSUMER_SECRET))
            .andRespond(withSuccess("""
                {"id": "API_0101", "result": {"accessTimeout": "1790772238970", "accessToken": "%s"}, "errMsg": "Success", "errCd": 0, "trId": "tr-auth"}"""
                .formatted(token), MediaType.APPLICATION_JSON));
    }

    private void expectSidoStage(String token, String body) {
        server.expect(requestTo(startsWith(BASE_URL + SgisDistrictSourceAdapter.STAGE_PATH)))
            .andExpect(queryParam("accessToken", token))
            // 시도 목록은 cd 없이 부른다 (cd 가 있으면 시군구 목록이 온다).
            .andExpect(request -> assertThat(UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams()).containsOnlyKeys("accessToken"))
            .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    private void expectSigunguStage(String token, String sidoCode, String body) {
        server.expect(requestTo(startsWith(BASE_URL + SgisDistrictSourceAdapter.STAGE_PATH)))
            .andExpect(queryParam("accessToken", token))
            .andExpect(queryParam("cd", sidoCode))
            .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    private void expectHadmarea(String token, String sidoCode, String body) {
        server.expect(requestTo(startsWith(BASE_URL + SgisDistrictSourceAdapter.HADMAREA_PATH)))
            .andExpect(queryParam("accessToken", token))
            .andExpect(queryParam("year", String.valueOf(YEAR)))
            .andExpect(queryParam("adm_cd", sidoCode))
            .andExpect(queryParam("low_search", "2"))
            .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    private static String envelope(String apiId, String result) {
        return "{\"id\": \"%s\", %s, \"errMsg\": \"Success\", \"errCd\": 0, \"trId\": \"tr-stage\"}".formatted(apiId, result);
    }

    private static String hadmarea(String features) {
        return "{\"type\": \"FeatureCollection\", \"features\": [%s], \"errMsg\": \"Success\", \"errCd\": 0, \"id\": \"API_0604\", \"trId\": \"tr-hadm\"}"
            .formatted(features);
    }
}
