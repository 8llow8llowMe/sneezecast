package com.sneezecast.domainlayer.notifiableimport.adapter.out.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.sneezecast.domainlayer.notifiableimport.application.exception.NotifiableImportErrorCode;
import com.sneezecast.domainlayer.notifiableimport.application.exception.NotifiableImportException;
import com.sneezecast.domainlayer.notifiableimport.application.model.KdcaCallBudget;
import com.sneezecast.domainlayer.notifiableimport.domain.model.NotifiableFetch;
import com.sneezecast.domainlayer.notifiableimport.domain.model.NotifiableRegionMeasure;
import com.sneezecast.domainlayer.notifiableimport.domain.model.NotifiableRegionRow;
import com.sneezecast.domainlayer.notifiableimport.domain.model.NotifiableWeeklyRow;
import com.sneezecast.global.properties.KdcaProperties;
import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
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
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * 응답 본문은 data-api-analysis §2-1 · §2-2 의 2026-09-30 실호출 형식을 줄여 옮겼다 — 봉투 {@code response{header{resultCode, resultMsg},
 * body{items{item[]}, pageNo(문자열), numOfRows(숫자), totalCount(문자열)}}}, 주별 item {@code {period, icdGroupNm, icdNm, resultVal}},
 * 시도 item {@code {year("2026년"), sidoCd, sidoNm, icdGroupNm("1급"), icdNm, resultVal}}, 인증 오류는 403 + XML. 실제 API 는 부르지 않는다.
 */
@ExtendWith(OutputCaptureExtension.class)
class KdcaNotifiableSourceAdapterTest {

    private static final String BASE_URL = "https://kdca.test/1790387/EIDAPIService";
    /** 가짜 키. Decoding 값처럼 + / = 를 넣어 인코딩 · 누출 검사를 함께 본다. */
    private static final String SERVICE_KEY = "fake-kdca-key+/=";
    private static final String SERVICE_KEY_ENCODED = "fake-kdca-key%2B%2F%3D";
    private static final int YEAR = 2026;
    private static final String SEOUL = "01";

    private static final String WEEKLY_PAGE = page("\"6\"", """
        {"period":"2026년 01주","icdGroupNm":"제1급","icdNm":"에볼라바이러스병","resultVal":"0"},
        {"period":"2026년 02주","icdGroupNm":"제1급","icdNm":"에볼라바이러스병","resultVal":"0"},
        {"period":"계","icdGroupNm":"제1급","icdNm":"에볼라바이러스병","resultVal":"0"},
        {"period":"2026년 01주","icdGroupNm":"제2급","icdNm":"@엠폭스","resultVal":"3"},
        {"period":"2026년 01주","icdGroupNm":"","icdNm":"수두","resultVal":"1,041"},
        {"period":"2026년 02주","icdGroupNm":"제2급","icdNm":"수두","resultVal":"-"}""");
    private static final String REGION_PAGE = page("\"4\"", """
        {"year":"2026년","sidoCd":"00","sidoNm":"전국","icdGroupNm":"1급","icdNm":"에볼라바이러스병","resultVal":"0"},
        {"year":"2026년","sidoCd":"01","sidoNm":"서울","icdGroupNm":"1급","icdNm":"에볼라바이러스병","resultVal":"0.00"},
        {"year":"2026년","sidoCd":"00","sidoNm":"전국","icdGroupNm":"2급","icdNm":"@엠폭스","resultVal":"0.31"},
        {"year":"2026년","sidoCd":"01","sidoNm":"서울","icdGroupNm":"2급","icdNm":"@엠폭스","resultVal":"0.12"}""");
    private static final String GATEWAY_XML = """
        <OpenAPI_ServiceResponse>
            <cmmMsgHeader>
                <errMsg>SERVICE ERROR</errMsg>
                <returnAuthMsg>SERVICE_KEY_IS_NOT_REGISTERED_ERROR</returnAuthMsg>
                <returnReasonCode>30</returnReasonCode>
            </cmmMsgHeader>
        </OpenAPI_ServiceResponse>""";

    private MockRestServiceServer server;
    private KdcaNotifiableSourceAdapter adapter;
    private KdcaCallBudget budget;

    @BeforeEach
    void setUp() {
        adapter = adapterWith(properties(SERVICE_KEY, 5000, 5));
        budget = new KdcaCallBudget(100);
    }

    @Test
    @DisplayName("주별 전국 한 페이지 — 계 행은 버리고, @ 를 뗀 키 · 원천 이름 · 쉼표 없는 값 · 제N급 분류로 읽는다")
    void fetchesWeeklySinglePage(CapturedOutput output) {
        server.expect(requestTo(startsWith(BASE_URL + KdcaNotifiableSourceAdapter.PERIOD_BASIC_PATH)))
            .andExpect(method(HttpMethod.GET))
            .andExpect(queryParam("resType", "2"))
            .andExpect(queryParam("pageNo", "1"))
            .andExpect(queryParam("numOfRows", "5000"))
            .andExpect(queryParam("searchPeriodType", "3"))
            .andExpect(queryParam("searchStartYear", "2026"))
            .andExpect(queryParam("searchEndYear", "2026"))
            .andRespond(withSuccess(WEEKLY_PAGE, MediaType.APPLICATION_JSON));

        NotifiableFetch<NotifiableWeeklyRow> fetch = adapter.fetchWeekly(YEAR, budget);

        server.verify();
        assertThat(fetch.rows()).containsExactly(
            new NotifiableWeeklyRow(2026, 1, "에볼라바이러스병", "에볼라바이러스병", "제1급", new BigDecimal("0")),
            new NotifiableWeeklyRow(2026, 2, "에볼라바이러스병", "에볼라바이러스병", "제1급", new BigDecimal("0")),
            new NotifiableWeeklyRow(2026, 1, "엠폭스", "@엠폭스", "제2급", new BigDecimal("3")),
            // 빈 분류는 null, 쉼표는 지운다
            new NotifiableWeeklyRow(2026, 1, "수두", "수두", null, new BigDecimal("1041")),
            // 숫자가 아닌 값은 0 이 아니라 null 이다
            new NotifiableWeeklyRow(2026, 2, "수두", "수두", "제2급", null));
        assertThat(fetch.rawRowCount()).isEqualTo(6);
        assertThat(fetch.nullValueCount()).isEqualTo(1);
        assertThat(fetch.calls()).isEqualTo(1);
        assertThat(fetch.byteLength()).isEqualTo(WEEKLY_PAGE.getBytes(StandardCharsets.UTF_8).length);
        assertThat(fetch.contentSha256()).isEqualTo(sha256(WEEKLY_PAGE));
        assertThat(budget.used()).isEqualTo(1);
        // 숫자가 아닌 값의 개수만 남기고 값 · 본문은 남기지 않는다.
        assertThat(output).contains("KDCA notifiable non-numeric values. operation=PeriodBasic(year=2026) nullValues=1");
        assertThat(output.toString()).doesNotContain("serviceKey=").doesNotContain("1,041");
    }

    @Test
    @DisplayName("시도 연별 — 연도 '2026년' 을 읽고, 함께 오는 전국 행은 버리고, '1급' 을 '제1급' 으로 맞춘다")
    void fetchesRegionAndDropsNationRows() {
        server.expect(requestTo(startsWith(BASE_URL + KdcaNotifiableSourceAdapter.REGION_PATH)))
            .andExpect(queryParam("resType", "2"))
            .andExpect(queryParam("pageNo", "1"))
            .andExpect(queryParam("searchType", "2"))
            .andExpect(queryParam("searchYear", "2026"))
            .andExpect(queryParam("searchSidoCd", SEOUL))
            .andRespond(withSuccess(REGION_PAGE, MediaType.APPLICATION_JSON));

        NotifiableFetch<NotifiableRegionRow> fetch = adapter.fetchRegion(YEAR, NotifiableRegionMeasure.INCIDENCE_PER_100K, SEOUL, budget);

        server.verify();
        assertThat(fetch.rows()).containsExactly(
            new NotifiableRegionRow(2026, SEOUL, "서울", "에볼라바이러스병", "에볼라바이러스병", "제1급", new BigDecimal("0.00")),
            new NotifiableRegionRow(2026, SEOUL, "서울", "엠폭스", "@엠폭스", "제2급", new BigDecimal("0.12")));
        assertThat(fetch.rawRowCount()).isEqualTo(4);
        assertThat(fetch.nullValueCount()).isZero();
        assertThat(fetch.calls()).isEqualTo(1);
    }

    @Test
    @DisplayName("시도 연별의 연도가 '년' 없이 와도 받고, 시도 이름은 원천 그대로 둔다")
    void acceptsRegionYearWithoutSuffix() {
        server.expect(requestTo(startsWith(BASE_URL + KdcaNotifiableSourceAdapter.REGION_PATH)))
            .andExpect(queryParam("searchType", "1"))
            .andExpect(queryParam("searchSidoCd", "05"))
            .andRespond(withSuccess(page("1", """
                {"year":"2026","sidoCd":"05","sidoNm":"광주 (현재 미사용)","icdGroupNm":"제3급","icdNm":"말라리아","resultVal":"12"}"""),
                MediaType.APPLICATION_JSON));

        NotifiableFetch<NotifiableRegionRow> fetch = adapter.fetchRegion(YEAR, NotifiableRegionMeasure.CASE_COUNT, "05", budget);

        assertThat(fetch.rows()).containsExactly(
            new NotifiableRegionRow(2026, "05", "광주 (현재 미사용)", "말라리아", "말라리아", "제3급", new BigDecimal("12")));
    }

    @Test
    @DisplayName("결과가 없으면 totalCount \"0\" + [] — 빈 결과로 정상 처리한다")
    void handlesEmptyResult() {
        String body = page("\"0\"", "");
        server.expect(requestTo(startsWith(BASE_URL + KdcaNotifiableSourceAdapter.PERIOD_BASIC_PATH)))
            .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        NotifiableFetch<NotifiableWeeklyRow> fetch = adapter.fetchWeekly(YEAR, budget);

        server.verify();
        assertThat(fetch.rows()).isEmpty();
        assertThat(fetch.rawRowCount()).isZero();
        assertThat(fetch.calls()).isEqualTo(1);
        assertThat(fetch.contentSha256()).isEqualTo(sha256(body));
    }

    @ParameterizedTest(name = "totalCount={0}")
    @ValueSource(strings = {"3", "\"3\""})
    @DisplayName("totalCount(숫자 · 문자열)까지 페이지를 넘기고, 해시 · 바이트 수는 두 본문을 이은 값이다")
    void followsPagesUntilTotalCount(String totalCount) {
        adapter = adapterWith(properties(SERVICE_KEY, 2, 5));
        String first = page(totalCount, """
            {"period":"2026년 01주","icdGroupNm":"제2급","icdNm":"수두","resultVal":"10"},
            {"period":"2026년 02주","icdGroupNm":"제2급","icdNm":"수두","resultVal":"20"}""");
        String second = page(totalCount, """
            {"period":"계","icdGroupNm":"제2급","icdNm":"수두","resultVal":"30"}""");
        server.expect(requestTo(startsWith(BASE_URL + KdcaNotifiableSourceAdapter.PERIOD_BASIC_PATH)))
            .andExpect(queryParam("pageNo", "1"))
            .andExpect(queryParam("numOfRows", "2"))
            .andRespond(withSuccess(first, MediaType.APPLICATION_JSON));
        server.expect(requestTo(startsWith(BASE_URL + KdcaNotifiableSourceAdapter.PERIOD_BASIC_PATH)))
            .andExpect(queryParam("pageNo", "2"))
            .andExpect(queryParam("numOfRows", "2"))
            .andRespond(withSuccess(second, MediaType.APPLICATION_JSON));

        NotifiableFetch<NotifiableWeeklyRow> fetch = adapter.fetchWeekly(YEAR, budget);

        server.verify();
        assertThat(fetch.rows()).extracting(NotifiableWeeklyRow::periodWeek).containsExactly(1, 2);
        assertThat(fetch.rawRowCount()).isEqualTo(3);
        assertThat(fetch.calls()).isEqualTo(2);
        assertThat(fetch.byteLength()).isEqualTo((first + second).getBytes(StandardCharsets.UTF_8).length);
        assertThat(fetch.contentSha256()).isEqualTo(sha256(first + second));
        assertThat(budget.used()).isEqualTo(2);
    }

    @Test
    @DisplayName("인증키 미등록(403 + XML) — JSON 을 요청해도 XML 이다. 사유 코드 · 메시지만 싣고 GATEWAY_REJECTED 다")
    void gatewayRejects403Xml() {
        server.expect(requestTo(startsWith(BASE_URL + KdcaNotifiableSourceAdapter.PERIOD_BASIC_PATH)))
            .andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.TEXT_XML).body(GATEWAY_XML));

        NotifiableImportException exception = assertFails(() -> adapter.fetchWeekly(YEAR, budget), NotifiableImportErrorCode.KDCA_GATEWAY_REJECTED,
            "httpStatus=403", "returnReasonCode=30", "returnAuthMsg=SERVICE_KEY_IS_NOT_REGISTERED_ERROR", "PeriodBasic(year=2026) pageNo=1");
        assertThat(exception.getMessage()).doesNotContain("cmmMsgHeader");
    }

    @Test
    @DisplayName("HTTP 200 이어도 본문(BOM · 앞 공백 무시)이 '<' 로 시작하면 GATEWAY_REJECTED 다 — 사유가 없으면 unknown")
    void gatewayRejects200Xml() {
        server.expect(requestTo(startsWith(BASE_URL + KdcaNotifiableSourceAdapter.REGION_PATH)))
            .andRespond(withSuccess("﻿ \n<OpenAPI_ServiceResponse><cmmMsgHeader><errMsg>SERVICE ERROR</errMsg></cmmMsgHeader></OpenAPI_ServiceResponse>",
                MediaType.TEXT_XML));

        assertFails(() -> adapter.fetchRegion(YEAR, NotifiableRegionMeasure.CASE_COUNT, SEOUL, budget), NotifiableImportErrorCode.KDCA_GATEWAY_REJECTED,
            "httpStatus=200", "returnReasonCode=unknown", "returnAuthMsg=unknown");
    }

    @Test
    @DisplayName("파라미터 오류는 HTTP 200 + response 감싸개 없는 header 다 — API_ERROR")
    void apiErrorWithoutResponseWrapper() {
        server.expect(requestTo(startsWith(BASE_URL + KdcaNotifiableSourceAdapter.PERIOD_BASIC_PATH)))
            .andRespond(withSuccess("""
                {"header":{"resultCode":"104","resultMsg":"DATATYPE_PARAMETER_ERROR"}}""", MediaType.APPLICATION_JSON));

        assertFails(() -> adapter.fetchWeekly(YEAR, budget), NotifiableImportErrorCode.KDCA_API_ERROR, "resultCode=104", "resultMsg=DATATYPE_PARAMETER_ERROR");
    }

    @Test
    @DisplayName("response.header.resultCode 가 00 이 아니면 API_ERROR 다")
    void apiErrorWithNonSuccessResultCode() {
        server.expect(requestTo(startsWith(BASE_URL + KdcaNotifiableSourceAdapter.PERIOD_BASIC_PATH)))
            .andRespond(withSuccess("""
                {"response":{"header":{"resultCode":"99","resultMsg":"UNKNOWN_ERROR"}}}""", MediaType.APPLICATION_JSON));

        assertFails(() -> adapter.fetchWeekly(YEAR, budget), NotifiableImportErrorCode.KDCA_API_ERROR, "resultCode=99", "resultMsg=UNKNOWN_ERROR");
    }

    @Test
    @DisplayName("XML 이 아닌 비-2xx 는 HTTP_ERROR 다")
    void httpErrorOnServerError() {
        server.expect(requestTo(startsWith(BASE_URL + KdcaNotifiableSourceAdapter.PERIOD_BASIC_PATH)))
            .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).contentType(MediaType.TEXT_PLAIN).body("Internal Server Error"));

        assertFails(() -> adapter.fetchWeekly(YEAR, budget), NotifiableImportErrorCode.KDCA_HTTP_ERROR, "httpStatus=500");
    }

    @Test
    @DisplayName("I/O · timeout 은 CALL_FAILED 다 — 가장 안쪽 원인만 붙이고 URL 을 담은 ResourceAccessException 은 붙이지 않는다")
    void callFailedOnTimeout() {
        server.expect(requestTo(startsWith(BASE_URL + KdcaNotifiableSourceAdapter.PERIOD_BASIC_PATH)))
            .andRespond(withException(new SocketTimeoutException("Read timed out")));

        NotifiableImportException exception = assertFails(() -> adapter.fetchWeekly(YEAR, budget), NotifiableImportErrorCode.KDCA_CALL_FAILED,
            "reason=SocketTimeoutException");
        assertThat(exception.getCause()).isInstanceOf(SocketTimeoutException.class);
        assertThat(budget.used()).isEqualTo(1);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidWeeklyBodies")
    @DisplayName("주별 응답 형식이 어긋나면 RESPONSE_INVALID 다")
    void weeklyResponseInvalid(String reason, String body) {
        server.expect(requestTo(startsWith(BASE_URL + KdcaNotifiableSourceAdapter.PERIOD_BASIC_PATH)))
            .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        assertFails(() -> adapter.fetchWeekly(YEAR, budget), NotifiableImportErrorCode.KDCA_RESPONSE_INVALID, reason);
    }

    static Stream<Arguments> invalidWeeklyBodies() {
        return Stream.of(
            Arguments.of("not json", "{\"response\":"),
            Arguments.of("response missing", "{\"result\":{}}"),
            Arguments.of("body missing", "{\"response\":{\"header\":{\"resultCode\":\"00\",\"resultMsg\":\"NORMAL_SERVICE\"}}}"),
            Arguments.of("totalCount missing", """
                {"response":{"header":{"resultCode":"00","resultMsg":"NORMAL_SERVICE"},"body":{"items":{"item":[]},"pageNo":"1","numOfRows":5000}}}"""),
            Arguments.of("items.item is not an array", """
                {"response":{"header":{"resultCode":"00","resultMsg":"NORMAL_SERVICE"},
                 "body":{"items":{"item":{"period":"2026년 01주","icdGroupNm":"제1급","icdNm":"에볼라바이러스병","resultVal":"0"}},
                         "pageNo":"1","numOfRows":5000,"totalCount":"1"}}}"""),
            Arguments.of("period year mismatch 2025년 01주", page("1", """
                {"period":"2025년 01주","icdGroupNm":"제1급","icdNm":"에볼라바이러스병","resultVal":"0"}""")),
            Arguments.of("period week out of range 2026년 54주", page("1", """
                {"period":"2026년 54주","icdGroupNm":"제1급","icdNm":"에볼라바이러스병","resultVal":"0"}""")),
            Arguments.of("unexpected period 2026년 01월", page("1", """
                {"period":"2026년 01월","icdGroupNm":"제1급","icdNm":"에볼라바이러스병","resultVal":"0"}""")),
            Arguments.of("unexpected period 2026년", page("1", """
                {"period":"2026년","icdGroupNm":"제1급","icdNm":"에볼라바이러스병","resultVal":"0"}""")),
            Arguments.of("icdNm missing", page("1", """
                {"period":"2026년 01주","icdGroupNm":"제1급","icdNm":" ","resultVal":"0"}""")),
            Arguments.of("icdNm has no name @@", page("1", """
                {"period":"2026년 01주","icdGroupNm":"제1급","icdNm":"@@","resultVal":"0"}""")),
            // 표식만 다른 같은 감염병도 같은 자연키다 — 조용히 접지 않는다.
            Arguments.of("duplicate row week=1 diseaseKey=엠폭스", page("2", """
                {"period":"2026년 01주","icdGroupNm":"제2급","icdNm":"@엠폭스","resultVal":"1"},
                {"period":"2026년 01주","icdGroupNm":"제2급","icdNm":"엠폭스","resultVal":"2"}""")),
            Arguments.of("more items than totalCount=1", page("1", """
                {"period":"2026년 01주","icdGroupNm":"제2급","icdNm":"수두","resultVal":"1"},
                {"period":"2026년 02주","icdGroupNm":"제2급","icdNm":"수두","resultVal":"2"}""")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidRegionBodies")
    @DisplayName("시도 응답 형식이 어긋나면 RESPONSE_INVALID 다")
    void regionResponseInvalid(String reason, String body) {
        server.expect(requestTo(startsWith(BASE_URL + KdcaNotifiableSourceAdapter.REGION_PATH)))
            .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        assertFails(() -> adapter.fetchRegion(YEAR, NotifiableRegionMeasure.CASE_COUNT, SEOUL, budget), NotifiableImportErrorCode.KDCA_RESPONSE_INVALID,
            reason);
    }

    static Stream<Arguments> invalidRegionBodies() {
        return Stream.of(
            Arguments.of("year mismatch 2025년", page("1", """
                {"year":"2025년","sidoCd":"01","sidoNm":"서울","icdGroupNm":"1급","icdNm":"에볼라바이러스병","resultVal":"0"}""")),
            Arguments.of("unexpected year 2026-01", page("1", """
                {"year":"2026-01","sidoCd":"01","sidoNm":"서울","icdGroupNm":"1급","icdNm":"에볼라바이러스병","resultVal":"0"}""")),
            // 시도 코드를 주면 전국 + 그 시도만 와야 한다.
            Arguments.of("unexpected sidoCd 02", page("1", """
                {"year":"2026년","sidoCd":"02","sidoNm":"부산","icdGroupNm":"1급","icdNm":"에볼라바이러스병","resultVal":"0"}""")),
            Arguments.of("sidoNm missing", page("1", """
                {"year":"2026년","sidoCd":"01","sidoNm":"","icdGroupNm":"1급","icdNm":"에볼라바이러스병","resultVal":"0"}""")),
            Arguments.of("duplicate row diseaseKey=에볼라바이러스병", page("2", """
                {"year":"2026년","sidoCd":"01","sidoNm":"서울","icdGroupNm":"1급","icdNm":"에볼라바이러스병","resultVal":"0"},
                {"year":"2026년","sidoCd":"01","sidoNm":"서울","icdGroupNm":"1급","icdNm":"에볼라바이러스병","resultVal":"1"}""")));
    }

    @Test
    @DisplayName("다음 페이지가 필요한데 maxPagesPerRequest 를 넘으면 RESPONSE_INVALID 다 — 일부만 받은 결과를 넘기지 않는다")
    void failsWhenPageLimitExceeded() {
        adapter = adapterWith(properties(SERVICE_KEY, 1, 1));
        server.expect(requestTo(startsWith(BASE_URL + KdcaNotifiableSourceAdapter.PERIOD_BASIC_PATH)))
            .andRespond(withSuccess(page("\"2\"", """
                {"period":"2026년 01주","icdGroupNm":"제2급","icdNm":"수두","resultVal":"1"}"""), MediaType.APPLICATION_JSON));

        assertFails(() -> adapter.fetchWeekly(YEAR, budget), NotifiableImportErrorCode.KDCA_RESPONSE_INVALID, "page limit exceeded maxPagesPerRequest=1");
        server.verify();
        assertThat(budget.used()).isEqualTo(1);
    }

    @Test
    @DisplayName("totalCount 에 못 미쳤는데 빈 페이지가 오면 RESPONSE_INVALID 다")
    void failsOnEmptyPageBeforeTotalCount() {
        adapter = adapterWith(properties(SERVICE_KEY, 1, 5));
        server.expect(requestTo(startsWith(BASE_URL + KdcaNotifiableSourceAdapter.PERIOD_BASIC_PATH)))
            .andExpect(queryParam("pageNo", "1"))
            .andRespond(withSuccess(page("\"2\"", """
                {"period":"2026년 01주","icdGroupNm":"제2급","icdNm":"수두","resultVal":"1"}"""), MediaType.APPLICATION_JSON));
        server.expect(requestTo(startsWith(BASE_URL + KdcaNotifiableSourceAdapter.PERIOD_BASIC_PATH)))
            .andExpect(queryParam("pageNo", "2"))
            .andRespond(withSuccess(page("\"2\"", ""), MediaType.APPLICATION_JSON));

        assertFails(() -> adapter.fetchWeekly(YEAR, budget), NotifiableImportErrorCode.KDCA_RESPONSE_INVALID, "empty page before totalCount=2", "pageNo=2");
        server.verify();
    }

    @Test
    @DisplayName("페이지 사이에 totalCount 가 바뀌면 RESPONSE_INVALID 다 — 행이 밀려 빠지거나 겹칠 수 있다")
    void failsWhenTotalCountChangesBetweenPages() {
        adapter = adapterWith(properties(SERVICE_KEY, 1, 5));
        server.expect(requestTo(startsWith(BASE_URL + KdcaNotifiableSourceAdapter.PERIOD_BASIC_PATH)))
            .andRespond(withSuccess(page("\"2\"", """
                {"period":"2026년 01주","icdGroupNm":"제2급","icdNm":"수두","resultVal":"1"}"""), MediaType.APPLICATION_JSON));
        server.expect(requestTo(startsWith(BASE_URL + KdcaNotifiableSourceAdapter.PERIOD_BASIC_PATH)))
            .andRespond(withSuccess(page("\"3\"", """
                {"period":"2026년 02주","icdGroupNm":"제2급","icdNm":"수두","resultVal":"2"}"""), MediaType.APPLICATION_JSON));

        assertFails(() -> adapter.fetchWeekly(YEAR, budget), NotifiableImportErrorCode.KDCA_RESPONSE_INVALID, "totalCount changed 2 -> 3");
    }

    @Test
    @DisplayName("호출 상한이 1 이면 두 번째 페이지를 부르기 전에 REQUEST_BUDGET_EXCEEDED 다")
    void stopsBeforeSecondPageWhenBudgetExhausted() {
        adapter = adapterWith(properties(SERVICE_KEY, 1, 5));
        budget = new KdcaCallBudget(1);
        server.expect(requestTo(startsWith(BASE_URL + KdcaNotifiableSourceAdapter.PERIOD_BASIC_PATH)))
            .andRespond(withSuccess(page("\"2\"", """
                {"period":"2026년 01주","icdGroupNm":"제2급","icdNm":"수두","resultVal":"1"}"""), MediaType.APPLICATION_JSON));

        assertFails(() -> adapter.fetchWeekly(YEAR, budget), NotifiableImportErrorCode.REQUEST_BUDGET_EXCEEDED, "used=1", "maxCallsPerRun=1");
        server.verify();
        assertThat(budget.used()).isEqualTo(1);
    }

    @Test
    @DisplayName("Decoding 키의 + / = 는 쿼리에서 정확히 한 번 %2B %2F %3D 로 인코딩된다 — 이중 인코딩(%25)되지 않는다")
    void encodesServiceKeyExactlyOnce() {
        adapter = adapterWith(properties("ab+cd/ef==", 5000, 5));
        server.expect(requestTo(startsWith(BASE_URL + KdcaNotifiableSourceAdapter.PERIOD_BASIC_PATH)))
            .andExpect(request -> {
                String rawQuery = request.getURI().getRawQuery();
                assertThat(rawQuery).contains("serviceKey=ab%2Bcd%2Fef%3D%3D&").doesNotContain("%25");
                assertThat(rawQuery.split("serviceKey=", -1)).hasSize(2);
            })
            .andRespond(withSuccess(page("\"0\"", ""), MediaType.APPLICATION_JSON));

        adapter.fetchWeekly(YEAR, budget);

        server.verify();
    }

    @Test
    @DisplayName("Encoding 값(% 포함)을 넣으면 아무 요청도 보내지 않고 SERVICE_KEY_LOOKS_ENCODED 다")
    void rejectsEncodedServiceKeyBeforeAnyCall() {
        adapter = adapterWith(properties(SERVICE_KEY_ENCODED, 5000, 5));

        NotifiableImportException exception = assertFails(() -> adapter.fetchWeekly(YEAR, budget),
            NotifiableImportErrorCode.KDCA_SERVICE_KEY_LOOKS_ENCODED, "'%'", "KDCA_API_SERVICE_KEY");
        assertThat(exception.getMessage()).doesNotContain("fake-kdca-key");
        server.verify();
        assertThat(budget.used()).isZero();
    }

    @ParameterizedTest(name = "serviceKey=\"{0}\"")
    @ValueSource(strings = {"", "  "})
    @DisplayName("인증키가 비어 있으면 아무 요청도 보내지 않고 CREDENTIALS_MISSING 이다 — 메시지에는 환경변수 이름만 있다")
    void rejectsMissingServiceKeyBeforeAnyCall(String serviceKey) {
        adapter = adapterWith(properties(serviceKey, 5000, 5));

        assertFails(() -> adapter.fetchRegion(YEAR, NotifiableRegionMeasure.CASE_COUNT, SEOUL, budget), NotifiableImportErrorCode.KDCA_CREDENTIALS_MISSING,
            "KDCA_API_SERVICE_KEY");
        server.verify();
        assertThat(budget.used()).isZero();
    }

    @Test
    @DisplayName("null 인증키도 CREDENTIALS_MISSING 이다")
    void rejectsNullServiceKey() {
        adapter = adapterWith(properties(null, 5000, 5));

        assertFails(() -> adapter.fetchWeekly(YEAR, budget), NotifiableImportErrorCode.KDCA_CREDENTIALS_MISSING, "KDCA_API_SERVICE_KEY");
        server.verify();
    }

    @Test
    @DisplayName("요청 인자가 어긋나면(전국 코드 00 · 세 자리 연도) 호출하지 않고 IllegalArgumentException 이다")
    void rejectsInvalidArgumentsBeforeAnyCall() {
        assertThatThrownBy(() -> adapter.fetchRegion(YEAR, NotifiableRegionMeasure.CASE_COUNT, "00", budget)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adapter.fetchWeekly(999, budget)).isInstanceOf(IllegalArgumentException.class);
        server.verify();
        assertThat(budget.used()).isZero();
    }

    @Test
    @DisplayName("전국 행은 왔는데 요청 시도 행이 하나도 없으면 RESPONSE_INVALID 다 — 빈 결과로 넘기지 않는다")
    void failsWhenRequestedSidoRowsMissing() {
        server.expect(requestTo(startsWith(BASE_URL + KdcaNotifiableSourceAdapter.REGION_PATH)))
            .andRespond(withSuccess(page("\"2\"", """
                {"year":"2026년","sidoCd":"00","sidoNm":"전국","icdGroupNm":"1급","icdNm":"에볼라바이러스병","resultVal":"0"},
                {"year":"2026년","sidoCd":"00","sidoNm":"전국","icdGroupNm":"2급","icdNm":"수두","resultVal":"10"}"""), MediaType.APPLICATION_JSON));

        assertFails(() -> adapter.fetchRegion(YEAR, NotifiableRegionMeasure.CASE_COUNT, SEOUL, budget), NotifiableImportErrorCode.KDCA_RESPONSE_INVALID,
            "sido rows missing nationRows=2");
    }

    @Test
    @DisplayName("전국 행 수와 시도 행 수가 다르면(시도 0 은 아님) 실패시키지 않고 WARN 으로 두 수만 남긴다")
    void warnsWhenSidoRowCountDiffersFromNation(CapturedOutput output) {
        server.expect(requestTo(startsWith(BASE_URL + KdcaNotifiableSourceAdapter.REGION_PATH)))
            .andRespond(withSuccess(page("\"3\"", """
                {"year":"2026년","sidoCd":"00","sidoNm":"전국","icdGroupNm":"1급","icdNm":"에볼라바이러스병","resultVal":"0"},
                {"year":"2026년","sidoCd":"00","sidoNm":"전국","icdGroupNm":"2급","icdNm":"수두","resultVal":"10"},
                {"year":"2026년","sidoCd":"01","sidoNm":"서울","icdGroupNm":"2급","icdNm":"수두","resultVal":"3"}"""), MediaType.APPLICATION_JSON));

        NotifiableFetch<NotifiableRegionRow> fetch = adapter.fetchRegion(YEAR, NotifiableRegionMeasure.CASE_COUNT, SEOUL, budget);

        assertThat(fetch.rows()).extracting(NotifiableRegionRow::diseaseKey).containsExactly("수두");
        assertThat(output).contains("WARN").contains("operation=Region(searchType=1, year=2026, sidoCd=01) nationRows=2 sidoRows=1");
    }

    @Test
    @DisplayName("봉투 표식이 없는 마크업 비-2xx(프록시 HTML 502)는 게이트웨이 거절이 아니라 HTTP_ERROR 다")
    void htmlBadGatewayIsHttpError() {
        server.expect(requestTo(startsWith(BASE_URL + KdcaNotifiableSourceAdapter.PERIOD_BASIC_PATH)))
            .andRespond(withStatus(HttpStatus.BAD_GATEWAY).contentType(MediaType.TEXT_HTML).body("<html><body><h1>502 Bad Gateway</h1></body></html>"));

        assertFails(() -> adapter.fetchWeekly(YEAR, budget), NotifiableImportErrorCode.KDCA_HTTP_ERROR, "httpStatus=502");
    }

    @Test
    @DisplayName("봉투 표식이 없는 마크업 2xx 는 RESPONSE_INVALID(unexpected markup) 다")
    void htmlOkIsResponseInvalid() {
        server.expect(requestTo(startsWith(BASE_URL + KdcaNotifiableSourceAdapter.PERIOD_BASIC_PATH)))
            .andRespond(withSuccess("<!DOCTYPE html><html><body>점검 중</body></html>", MediaType.TEXT_HTML));

        assertFails(() -> adapter.fetchWeekly(YEAR, budget), NotifiableImportErrorCode.KDCA_RESPONSE_INVALID, "unexpected markup");
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("columnViolatingRows")
    @DisplayName("컬럼 제약을 넘는 행(길이 · DECIMAL(12,2) 범위)은 자르거나 반올림하지 않고 RESPONSE_INVALID 다 — 메시지에 값을 싣지 않는다")
    void rejectsRowsViolatingColumnConstraints(String reason, String item) {
        server.expect(requestTo(startsWith(BASE_URL + KdcaNotifiableSourceAdapter.REGION_PATH)))
            .andRespond(withSuccess(page("1", item), MediaType.APPLICATION_JSON));

        NotifiableImportException exception = assertFails(() -> adapter.fetchRegion(YEAR, NotifiableRegionMeasure.CASE_COUNT, SEOUL, budget),
            NotifiableImportErrorCode.KDCA_RESPONSE_INVALID, reason);
        assertThat(exception.getMessage()).doesNotContain("가".repeat(21));
    }

    static Stream<Arguments> columnViolatingRows() {
        String row = """
            {"year":"2026년","sidoCd":"01","sidoNm":"%s","icdGroupNm":"%s","icdNm":"%s","resultVal":"%s"}""";
        return Stream.of(
            Arguments.of("diseaseKey exceeds 100", row.formatted("서울", "1급", "가".repeat(101), "0")),
            // 메시지용 clip(100자)이 데이터에 끼면 150자가 잘린 채 통과할 수 있다 — 원문 길이로 거절해야 한다.
            Arguments.of("diseaseGroup exceeds 20", row.formatted("서울", "가".repeat(150), "수두", "0")),
            Arguments.of("sidoName exceeds 30", row.formatted("가".repeat(31), "1급", "수두", "0")),
            Arguments.of("at most 2 decimal places", row.formatted("서울", "1급", "수두", "0.125")),
            Arguments.of("at most 2 decimal places", row.formatted("서울", "1급", "수두", "-1")),
            Arguments.of("at most 2 decimal places", row.formatted("서울", "1급", "수두", "1E+12")));
    }

    @Test
    @DisplayName("컬럼 한계 안의 값은 원문 그대로 받는다 — 100자 이름 · 30자 시도 이름 · 9,999,999,999.99")
    void keepsValuesAtColumnLimits() {
        String name = "@" + "가".repeat(99);
        String sidoName = "나".repeat(30);
        server.expect(requestTo(startsWith(BASE_URL + KdcaNotifiableSourceAdapter.REGION_PATH)))
            .andRespond(withSuccess(page("1", """
                {"year":"2026년","sidoCd":"01","sidoNm":"%s","icdGroupNm":"1급","icdNm":"%s","resultVal":"9,999,999,999.99"}"""
                .formatted(sidoName, name)), MediaType.APPLICATION_JSON));

        NotifiableFetch<NotifiableRegionRow> fetch = adapter.fetchRegion(YEAR, NotifiableRegionMeasure.CASE_COUNT, SEOUL, budget);

        assertThat(fetch.rows()).singleElement().satisfies(row -> {
            assertThat(row.diseaseName()).isEqualTo(name).hasSize(100);
            assertThat(row.diseaseKey()).hasSize(99);
            assertThat(row.sidoName()).isEqualTo(sidoName);
            assertThat(row.value()).isEqualByComparingTo("9999999999.99");
        });
    }

    /** 예외 코드 · 메시지를 확인하고, 메시지 · cause 체인 어디에도 인증키와 URL 쿼리가 없음을 단언한다. */
    private static NotifiableImportException assertFails(Supplier<?> call, NotifiableImportErrorCode expected, String... messageParts) {
        NotifiableImportException[] thrown = new NotifiableImportException[1];
        assertThatThrownBy(call::get)
            .isInstanceOfSatisfying(NotifiableImportException.class, exception -> {
                assertThat(exception.getErrorCode()).isEqualTo(expected);
                assertThat(exception.getMessage()).startsWith("[" + expected.getCode() + "]").contains(messageParts);
                thrown[0] = exception;
            });
        assertNoSecretInChain(thrown[0]);
        return thrown[0];
    }

    private static void assertNoSecretInChain(Throwable exception) {
        for (Throwable current = exception; current != null; current = current.getCause()) {
            for (String text : List.of(String.valueOf(current.getMessage()), current.toString())) {
                assertThat(text).as(current.getClass().getName())
                    .doesNotContain(SERVICE_KEY)
                    .doesNotContain(SERVICE_KEY_ENCODED)
                    .doesNotContain("serviceKey=");
            }
        }
    }

    private KdcaNotifiableSourceAdapter adapterWith(KdcaProperties properties) {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        return new KdcaNotifiableSourceAdapter(builder.build(), properties);
    }

    private static KdcaProperties properties(String serviceKey, int pageSize, int maxPages) {
        return new KdcaProperties(BASE_URL, serviceKey, Duration.ofSeconds(3), Duration.ofSeconds(30), pageSize, maxPages);
    }

    /** {@code totalCount} 는 JSON 토큰 그대로 받는다 — 문자열({@code "\"2747\""}) · 숫자({@code "2747"}). */
    private static String page(String totalCount, String items) {
        return """
            {"response":{"header":{"resultCode":"00","resultMsg":"NORMAL_SERVICE"},
             "body":{"items":{"item":[%s]},"pageNo":"1","numOfRows":5000,"totalCount":%s}}}""".formatted(items, totalCount);
    }

    private static String sha256(String body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
