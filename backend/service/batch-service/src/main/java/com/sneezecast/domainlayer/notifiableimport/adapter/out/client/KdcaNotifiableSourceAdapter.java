package com.sneezecast.domainlayer.notifiableimport.adapter.out.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.sneezecast.domainlayer.notifiableimport.application.exception.NotifiableImportErrorCode;
import com.sneezecast.domainlayer.notifiableimport.application.exception.NotifiableImportException;
import com.sneezecast.domainlayer.notifiableimport.application.model.KdcaCallBudget;
import com.sneezecast.domainlayer.notifiableimport.application.port.out.NotifiableSourcePort;
import com.sneezecast.domainlayer.notifiableimport.domain.model.NotifiableFetch;
import com.sneezecast.domainlayer.notifiableimport.domain.model.NotifiableRegionMeasure;
import com.sneezecast.domainlayer.notifiableimport.domain.model.NotifiableRegionRow;
import com.sneezecast.domainlayer.notifiableimport.domain.model.NotifiableRequest;
import com.sneezecast.domainlayer.notifiableimport.domain.model.NotifiableWeeklyRow;
import com.sneezecast.global.properties.KdcaProperties;
import java.math.BigDecimal;
import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriBuilder;

/**
 * 공공데이터포털 질병관리청 전수신고 감염병 발생현황 API 로 주별 전국({@code /PeriodBasic}) · 시도 연별({@code /Region}) 값을 받는다
 * (data-api-analysis §2).
 *
 * <p><b>판정 규칙.</b> 실패도 HTTP 200 으로 오는 경우가 있어 상태 코드만 보고 성공으로 보지 않는다 (§2-2). 본문이 공공데이터포털 XML 봉투
 * ({@code OpenAPI_ServiceResponse} · {@code cmmMsgHeader} 표식)면 상태와 무관하게 게이트웨이 거절(인증키 미등록은 403 + XML)이다. 표식이 없는
 * 마크업(프록시 HTML 등)은 비-2xx 면 HTTP 오류, 2xx 면 응답 해석 실패다. 그 밖의 비-2xx 는 HTTP 오류, JSON 은 {@code resultCode} 로 판정한다.
 * 해석 규칙은 {@link KdcaNotifiableResponseParser}.
 *
 * <p><b>페이지.</b> {@code pageNo=1} 부터 {@code totalCount} 까지 넘겨 한 결과로 합친다. 연간 주별이 약 3,600행이라 {@code numOfRows=5000}
 * 이면 보통 1회로 끝난다. 상한({@code maxPagesPerRequest})을 넘거나 빈 페이지가 오면 실패한다 — 일부만 받은 결과를 성공으로 넘기지 않는다.
 * 호출 직전마다 실행 예산을 쓴다.
 *
 * <p><b>비밀값.</b> 인증키는 요청 URL 의 쿼리에 실린다. Decoding 값을 URI 변수로만 넣어 {@code + / =} 가 한 번만 인코딩되게 하고, Encoding 값
 * ({@code %} 포함)은 호출 전에 막는다. URL 을 담은 {@link RestClientException} 은 예외 원인으로 붙이지 않고, 응답 본문은 로그에 남기지 않는다.
 */
@Slf4j
@Component
public class KdcaNotifiableSourceAdapter implements NotifiableSourcePort {

    static final String PERIOD_BASIC_PATH = "/PeriodBasic";
    static final String REGION_PATH = "/Region";

    /** {@code resType} 2 = JSON. */
    private static final int RES_TYPE_JSON = 2;
    /** {@code searchPeriodType} 3 = 주. */
    private static final int SEARCH_PERIOD_TYPE_WEEK = 3;

    private final RestClient restClient;
    private final KdcaProperties properties;

    public KdcaNotifiableSourceAdapter(@Qualifier("kdcaRestClient") RestClient restClient, KdcaProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    @Override
    public NotifiableFetch<NotifiableWeeklyRow> fetchWeekly(int year, KdcaCallBudget budget) {
        // 인자 검증만 한다 (연도 네 자리). 어기면 IllegalArgumentException — 호출하는 쪽의 잘못이다.
        NotifiableRequest.weekly(year);
        String operation = "PeriodBasic(year=%d)".formatted(year);
        Pages pages = fetchPages(operation, budget, builder -> builder.path(PERIOD_BASIC_PATH)
            .queryParam("searchPeriodType", "{searchPeriodType}")
            .queryParam("searchStartYear", "{year}")
            .queryParam("searchEndYear", "{year}"), Map.of("searchPeriodType", SEARCH_PERIOD_TYPE_WEEK, "year", year));

        List<NotifiableWeeklyRow> rows = new ArrayList<>();
        Set<WeeklyKey> keys = new HashSet<>();
        for (JsonNode item : pages.items()) {
            Optional<NotifiableWeeklyRow> parsed = KdcaNotifiableResponseParser.weeklyRow(operation, year, item);
            if (parsed.isEmpty()) {
                continue;
            }
            NotifiableWeeklyRow row = parsed.get();
            // 같은 주 · 같은 감염병이 두 번 오면 어느 쪽이 맞는지 알 수 없다. 접지 않고 실패한다.
            if (!keys.add(new WeeklyKey(row.periodYear(), row.periodWeek(), row.diseaseKey()))) {
                throw KdcaNotifiableResponseParser.invalid(operation, "duplicate row week=%d diseaseKey=%s"
                    .formatted(row.periodWeek(), KdcaNotifiableResponseParser.clip(row.diseaseKey())));
            }
            rows.add(row);
        }
        return toFetch(operation, rows, NotifiableWeeklyRow::value, pages);
    }

    @Override
    public NotifiableFetch<NotifiableRegionRow> fetchRegion(int year, NotifiableRegionMeasure measure, String sidoCode, KdcaCallBudget budget) {
        // 인자 검증만 한다 (연도 네 자리 · 지표 · 시도 코드 두 자리이고 00 아님). 어기면 IllegalArgumentException.
        NotifiableRequest.region(year, measure, sidoCode);
        String operation = "Region(searchType=%d, year=%d, sidoCd=%s)".formatted(measure.getSearchType(), year, sidoCode);
        Pages pages = fetchPages(operation, budget, builder -> builder.path(REGION_PATH)
            .queryParam("searchType", "{searchType}")
            .queryParam("searchYear", "{year}")
            .queryParam("searchSidoCd", "{sidoCode}"), Map.of("searchType", measure.getSearchType(), "year", year, "sidoCode", sidoCode));

        List<NotifiableRegionRow> rows = new ArrayList<>();
        Set<String> diseaseKeys = new HashSet<>();
        int nationRows = 0;
        for (JsonNode item : pages.items()) {
            Optional<NotifiableRegionRow> parsed = KdcaNotifiableResponseParser.regionRow(operation, year, sidoCode, item);
            if (parsed.isEmpty()) {
                // 빈 값은 전국 행뿐이다 — 다른 시도 코드는 파서가 실패시킨다.
                nationRows++;
                continue;
            }
            NotifiableRegionRow row = parsed.get();
            // 시도 코드는 요청 코드 하나뿐이라 감염병 키만 겹치지 않으면 된다.
            if (!diseaseKeys.add(row.diseaseKey())) {
                throw KdcaNotifiableResponseParser.invalid(operation, "duplicate row diseaseKey=" + KdcaNotifiableResponseParser.clip(row.diseaseKey()));
            }
            rows.add(row);
        }
        // 전국 행은 왔는데 요청 시도 행이 하나도 없으면, 시도 코드가 원천에서 사라졌거나 응답이 잘린 것이다. 빈 결과로 넘기지 않는다.
        if (nationRows > 0 && rows.isEmpty()) {
            throw KdcaNotifiableResponseParser.invalid(operation, "sido rows missing nationRows=" + nationRows);
        }
        // 감염병 목록이 시도마다 다를 수 있어 수가 달라도 실패시키지 않는다. 원인을 되짚을 수 있게 남긴다.
        if (nationRows != rows.size()) {
            log.warn("KDCA region row count differs from nation rows. operation={} nationRows={} sidoRows={}", operation, nationRows, rows.size());
        }
        return toFetch(operation, rows, NotifiableRegionRow::value, pages);
    }

    /**
     * {@code totalCount} 까지 페이지를 넘긴다. 공통 쿼리({@code serviceKey} · {@code resType} · {@code pageNo} · {@code numOfRows})는 여기서 붙인다.
     *
     * @param searchQuery     오퍼레이션 경로와 조회 조건 쿼리 (값은 {@code {이름}} 템플릿)
     * @param searchVariables 조회 조건 템플릿 값
     */
    private Pages fetchPages(String operation, KdcaCallBudget budget, UnaryOperator<UriBuilder> searchQuery, Map<String, Object> searchVariables) {
        requireServiceKey();
        MessageDigest digest = sha256();
        List<JsonNode> items = new ArrayList<>();
        int totalCount = -1;
        int byteLength = 0;
        int calls = 0;
        for (int pageNo = 1; ; pageNo++) {
            if (pageNo > properties.maxPagesPerRequest()) {
                throw KdcaNotifiableResponseParser.invalid(operation, "page limit exceeded maxPagesPerRequest=%d totalCount=%d received=%d"
                    .formatted(properties.maxPagesPerRequest(), totalCount, items.size()));
            }
            String pageOperation = "%s pageNo=%d".formatted(operation, pageNo);
            Map<String, Object> variables = new HashMap<>(searchVariables);
            variables.put("serviceKey", properties.serviceKey());
            variables.put("resType", RES_TYPE_JSON);
            variables.put("pageNo", pageNo);
            variables.put("numOfRows", properties.pageSize());

            budget.consume();
            calls++;
            byte[] body = fetch(pageOperation, builder -> searchQuery.apply(builder)
                .queryParam("serviceKey", "{serviceKey}")
                .queryParam("resType", "{resType}")
                .queryParam("pageNo", "{pageNo}")
                .queryParam("numOfRows", "{numOfRows}")
                .build(variables));
            digest.update(body);
            byteLength = Math.addExact(byteLength, body.length);

            KdcaNotifiableResponseParser.Page page = KdcaNotifiableResponseParser.parsePage(pageOperation, body);
            if (totalCount < 0) {
                totalCount = page.totalCount();
            } else if (page.totalCount() != totalCount) {
                // 페이지 사이에 원천이 바뀌면 행이 밀려 빠지거나 겹친다.
                throw KdcaNotifiableResponseParser.invalid(pageOperation, "totalCount changed %d -> %d".formatted(totalCount, page.totalCount()));
            }
            items.addAll(page.items());
            if (items.size() > totalCount) {
                throw KdcaNotifiableResponseParser.invalid(pageOperation, "more items than totalCount=%d received=%d".formatted(totalCount, items.size()));
            }
            if (items.size() == totalCount) {
                break;
            }
            if (page.items().isEmpty()) {
                throw KdcaNotifiableResponseParser.invalid(pageOperation, "empty page before totalCount=%d received=%d".formatted(totalCount, items.size()));
            }
        }
        return new Pages(items, HexFormat.of().formatHex(digest.digest()), byteLength, calls);
    }

    private byte[] fetch(String operation, Function<UriBuilder, URI> uri) {
        ResponseEntity<byte[]> response;
        try {
            response = restClient.get().uri(uri).retrieve().toEntity(byte[].class);
        } catch (RestClientResponseException exception) {
            // 인증키 미등록은 403 + XML 봉투다. 봉투의 사유 코드만 싣고 본문 · URL 은 싣지 않는다. 봉투가 아니면(HTML 502 등) 상태 코드만 남긴다.
            int status = exception.getStatusCode().value();
            byte[] errorBody = exception.getResponseBodyAsByteArray();
            if (KdcaNotifiableResponseParser.isGatewayEnvelope(errorBody)) {
                throw KdcaNotifiableResponseParser.gatewayRejected(operation, status, errorBody);
            }
            throw new NotifiableImportException(NotifiableImportErrorCode.KDCA_HTTP_ERROR, operation, status);
        } catch (RestClientException exception) {
            // RestClientException 메시지에는 요청 URL(인증키 포함)이 실린다. 가장 안쪽 원인(I/O · timeout)만 붙인다.
            Throwable rootCause = NestedExceptionUtils.getMostSpecificCause(exception);
            Throwable cause = rootCause == exception ? null : rootCause;
            throw new NotifiableImportException(NotifiableImportErrorCode.KDCA_CALL_FAILED, cause, operation, rootCause.getClass().getSimpleName());
        }
        byte[] body = response.getBody();
        if (body == null || body.length == 0) {
            throw KdcaNotifiableResponseParser.invalid(operation, "empty body");
        }
        if (KdcaNotifiableResponseParser.isGatewayEnvelope(body)) {
            throw KdcaNotifiableResponseParser.gatewayRejected(operation, response.getStatusCode().value(), body);
        }
        if (KdcaNotifiableResponseParser.looksLikeMarkup(body)) {
            // 봉투 표식이 없는 HTML 등 — 키 · 트래픽 문제로 단정할 근거가 없다.
            throw KdcaNotifiableResponseParser.invalid(operation, "unexpected markup");
        }
        return body;
    }

    private void requireServiceKey() {
        if (!properties.hasServiceKey()) {
            throw new NotifiableImportException(NotifiableImportErrorCode.KDCA_CREDENTIALS_MISSING);
        }
        if (properties.serviceKeyLooksEncoded()) {
            throw new NotifiableImportException(NotifiableImportErrorCode.KDCA_SERVICE_KEY_LOOKS_ENCODED);
        }
    }

    private static <T> NotifiableFetch<T> toFetch(String operation, List<T> rows, Function<T, BigDecimal> value, Pages pages) {
        int nullValueCount = (int) rows.stream().filter(row -> value.apply(row) == null).count();
        if (nullValueCount > 0) {
            // 원천이 비우거나 숫자가 아닌 값을 준 행. 값 자체는 남기지 않는다.
            log.info("KDCA notifiable non-numeric values. operation={} nullValues={}", operation, nullValueCount);
        }
        log.info("KDCA notifiable fetched. operation={} rows={} rawRows={} calls={} bytes={}",
            operation, rows.size(), pages.items().size(), pages.calls(), pages.byteLength());
        return new NotifiableFetch<>(rows, pages.contentSha256(), pages.byteLength(), pages.items().size(), nullValueCount, pages.calls());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            // 모든 JVM 이 SHA-256 을 갖춰야 한다 (MessageDigest 명세).
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private record Pages(List<JsonNode> items, String contentSha256, int byteLength, int calls) {

    }

    private record WeeklyKey(int year, int week, String diseaseKey) {

    }
}
