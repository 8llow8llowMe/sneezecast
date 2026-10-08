package com.sneezecast.domainlayer.sentinelimport.adapter.out.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.sneezecast.domainlayer.sentinelimport.application.exception.SentinelImportErrorCode;
import com.sneezecast.domainlayer.sentinelimport.application.exception.SentinelImportException;
import com.sneezecast.domainlayer.sentinelimport.application.model.SentinelCallBudget;
import com.sneezecast.domainlayer.sentinelimport.application.port.out.SentinelSourcePort;
import com.sneezecast.domainlayer.sentinelimport.domain.model.SentinelFetch;
import com.sneezecast.domainlayer.sentinelimport.domain.model.SentinelIliRow;
import com.sneezecast.domainlayer.sentinelimport.domain.model.SentinelPathogenRow;
import com.sneezecast.domainlayer.sentinelimport.domain.model.SentinelProgram;
import com.sneezecast.domainlayer.sentinelimport.domain.model.SentinelRequest;
import com.sneezecast.global.properties.SentinelPortalProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * 감염병포털 표본감시 화면 데이터(JSON)로 주별 병원체 신고 수 · 인플루엔자 의사환자 분율을 받는다 (data-api-analysis §3).
 *
 * <p><b>요청 하나 = HTTP 두 번.</b> 먼저 화면({@code /{icdNm}.do})을 열어 세션 쿠키를 받고, 그 쿠키로 데이터
 * ({@code /{icdNm}ListAjax.do}, {@code X-Requested-With: XMLHttpRequest} + 폼)를 받는다. 화면 응답 본문은 쓰지 않는다. 호출 직전마다 실행
 * 예산({@link SentinelCallBudget})을 쓴다.
 *
 * <p><b>요청 간격.</b> 짧게 연달아 보내면 연결이 끊긴다 (§3-5). 마지막 호출 시각을 빈 안에 기억해 다음 호출 전까지
 * {@code sentinel-portal.request-interval} 이 지나도록 기다린다 (첫 호출은 기다리지 않는다). 주 1회 실행이라 빈 하나를 한 스레드에서만 쓴다.
 *
 * <p><b>공개 API 가 아니다.</b> 열 구성이 바뀌면 다른 병원체에 값이 들어가므로 {@code captionList} 를 기대 목록과 대조해 멈춘다
 * ({@link SentinelPortalResponseParser}). 비-2xx 는 {@code PORTAL_HTTP_ERROR}, I/O · timeout 은 {@code PORTAL_CALL_FAILED} 이고, 응답 본문과
 * 요청 URL 은 예외 · 로그에 남기지 않는다.
 */
@Slf4j
@Component
public class SentinelPortalSourceAdapter implements SentinelSourcePort {

    static final String SCREEN_PATH = "/{icdNm}.do";
    static final String DATA_PATH = "/{icdNm}ListAjax.do";
    static final String AJAX_HEADER = "X-Requested-With";
    static final String AJAX_HEADER_VALUE = "XMLHttpRequest";
    /** 포털 세션 쿠키. 이 쿠키 없이 데이터를 요청하면 데이터가 아니라 화면 · 오류가 온다. */
    static final String SESSION_COOKIE = "JSESSIONID";

    private final RestClient restClient;
    private final SentinelPortalProperties properties;
    private final Clock clock;
    private final Sleeper sleeper;

    private Instant lastCallAt;

    @Autowired
    public SentinelPortalSourceAdapter(@Qualifier("sentinelPortalRestClient") RestClient restClient, SentinelPortalProperties properties) {
        this(restClient, properties, Clock.systemUTC(), duration -> Thread.sleep(duration.toMillis()));
    }

    /** 테스트가 실제로 기다리지 않게 시각 · 대기를 바꿔 끼운다. */
    SentinelPortalSourceAdapter(RestClient restClient, SentinelPortalProperties properties, Clock clock, Sleeper sleeper) {
        this.restClient = restClient;
        this.properties = properties;
        this.clock = clock;
        this.sleeper = sleeper;
    }

    @Override
    public SentinelFetch<SentinelPathogenRow> fetchPathogens(SentinelRequest request, SentinelCallBudget budget) {
        requireProgram(request, true);
        String operation = operation(request);
        Data data = fetch(operation, request, budget);
        SentinelPortalResponseParser.Parsed<SentinelPathogenRow> parsed =
            SentinelPortalResponseParser.pathogens(operation, data.value(), request);
        return toFetch(operation, parsed, data);
    }

    @Override
    public SentinelFetch<SentinelIliRow> fetchInfluenza(SentinelRequest request, SentinelCallBudget budget) {
        requireProgram(request, false);
        String operation = operation(request);
        Data data = fetch(operation, request, budget);
        SentinelPortalResponseParser.Parsed<SentinelIliRow> parsed =
            SentinelPortalResponseParser.influenza(operation, data.value(), request);
        return toFetch(operation, parsed, data);
    }

    /** 화면을 열어 세션 쿠키를 받고, 그 쿠키로 데이터를 받는다. */
    private Data fetch(String operation, SentinelRequest request, SentinelCallBudget budget) {
        budget.consume();
        String cookie = openScreen(operation + " screen", request.program());
        budget.consume();
        byte[] body = postData(operation + " data", request, cookie);
        return new Data(SentinelPortalResponseParser.value(operation, body), body);
    }

    /**
     * 화면 응답의 {@code Set-Cookie} 에서 쿠키 이름 = 값만 모은다 ({@code JSESSIONID} · {@code clientid}). 본문은 쓰지 않는다. 세션 쿠키
     * ({@value #SESSION_COOKIE})가 없으면 데이터 요청이 데이터가 아니라 화면 · 오류를 받으므로 여기서 멈춘다. 쿠키 값은 예외 · 로그에 싣지 않는다.
     */
    private String openScreen(String operation, SentinelProgram program) {
        ResponseEntity<Void> response = call(operation,
            () -> restClient.get().uri(SCREEN_PATH, program.getIcdNm()).retrieve().toBodilessEntity());
        List<String> setCookies = response.getHeaders().getOrEmpty(HttpHeaders.SET_COOKIE);
        Map<String, String> cookies = new LinkedHashMap<>();
        for (String setCookie : setCookies) {
            // 이름 = 값만 쓴다. Path · HttpOnly 같은 속성은 다음 요청에 싣지 않는다.
            String pair = setCookie.split(";", 2)[0].trim();
            int separator = pair.indexOf('=');
            if (separator > 0) {
                cookies.put(pair.substring(0, separator), pair.substring(separator + 1));
            }
        }
        if (!cookies.containsKey(SESSION_COOKIE)) {
            throw new SentinelImportException(SentinelImportErrorCode.PORTAL_SESSION_MISSING, operation);
        }
        return cookies.entrySet().stream().map(entry -> entry.getKey() + "=" + entry.getValue()).collect(Collectors.joining("; "));
    }

    private byte[] postData(String operation, SentinelRequest request, String cookie) {
        MultiValueMap<String, String> form = form(request);
        ResponseEntity<byte[]> response = call(operation, () -> restClient.post()
            .uri(DATA_PATH, request.program().getIcdNm())
            .header(AJAX_HEADER, AJAX_HEADER_VALUE)
            .header(HttpHeaders.COOKIE, cookie)
            .contentType(new MediaType(MediaType.APPLICATION_FORM_URLENCODED, StandardCharsets.UTF_8))
            .body(form)
            .retrieve()
            .toEntity(byte[].class));
        byte[] body = response.getBody();
        if (body == null || body.length == 0) {
            throw SentinelPortalResponseParser.invalid(operation, "empty body");
        }
        return body;
    }

    /**
     * 화면 폼 파라미터 (data-api-analysis §3-3). 빈 값도 보낸다 — 화면이 보내는 그대로다.
     *
     * <p>급성호흡기 · 장관감염증은 주 범위({@code dayCheck=1} 주별)이고 연도를 넘겨도 한 번에 온다. 인플루엔자는 절기 시작 · 끝 연도다.
     */
    private static MultiValueMap<String, String> form(SentinelRequest request) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        if (request.program().isPathogenWeekly()) {
            form.add("startYear", String.valueOf(request.from().year()));
            form.add("startWeek", "%02d".formatted(request.from().week()));
            form.add("endYear", String.valueOf(request.to().year()));
            form.add("endWeek", "%02d".formatted(request.to().week()));
            form.add("dayCheck", "1");
            form.add("infectiousGubun", "");
            form.add("subInfectious", "");
            form.add("age", "");
            return form;
        }
        form.add("startYear", String.valueOf(request.seasonStartYear()));
        form.add("endYear", String.valueOf(request.seasonEndYear()));
        form.add("age", "");
        form.add("intoDivi", "1");
        form.add("sido", "");
        return form;
    }

    /** 호출 전에 간격을 지키고, 실패를 에러코드로 바꾼다. */
    private <T> ResponseEntity<T> call(String operation, RestCall<T> restCall) {
        awaitInterval(operation);
        ResponseEntity<T> response;
        try {
            response = restCall.execute();
        } catch (RestClientResponseException exception) {
            // 포털이 상태 코드로 거절했다. 본문 · URL 은 싣지 않는다.
            throw new SentinelImportException(SentinelImportErrorCode.PORTAL_HTTP_ERROR, operation, exception.getStatusCode().value());
        } catch (RestClientException exception) {
            // RestClientException 메시지에는 요청 URL 이 실린다. 가장 안쪽 원인(I/O · timeout)만 붙인다.
            Throwable rootCause = NestedExceptionUtils.getMostSpecificCause(exception);
            Throwable cause = rootCause == exception ? null : rootCause;
            throw new SentinelImportException(SentinelImportErrorCode.PORTAL_CALL_FAILED, cause, operation, rootCause.getClass().getSimpleName());
        } finally {
            lastCallAt = clock.instant();
        }
        // retrieve() 는 4xx · 5xx 만 예외로 올린다. 리다이렉트(3xx)는 따라가지 않으므로 여기서 거절한다.
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new SentinelImportException(SentinelImportErrorCode.PORTAL_HTTP_ERROR, operation, response.getStatusCode().value());
        }
        return response;
    }

    /** 마지막 호출로부터 {@code requestInterval} 이 지나도록 기다린다. 첫 호출은 기다리지 않는다. */
    private void awaitInterval(String operation) {
        if (lastCallAt == null) {
            return;
        }
        Duration wait = properties.requestInterval().minus(Duration.between(lastCallAt, clock.instant()));
        if (wait.isZero() || wait.isNegative()) {
            return;
        }
        try {
            sleeper.sleep(wait);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new SentinelImportException(SentinelImportErrorCode.PORTAL_CALL_FAILED, exception, operation, exception.getClass().getSimpleName());
        }
    }

    private static void requireProgram(SentinelRequest request, boolean pathogenWeekly) {
        if (request.program().isPathogenWeekly() != pathogenWeekly) {
            throw new IllegalArgumentException("unexpected program for this operation. program=" + request.program());
        }
    }

    /** 예: {@code ari(2026-32~2026-39)} · {@code influ(2026-2027)}. 조회 조건만 담고 URL 은 담지 않는다. */
    private static String operation(SentinelRequest request) {
        String range = request.requestKey().substring(request.requestKey().lastIndexOf(':') + 1);
        return "%s(%s)".formatted(request.program().getIcdNm(), range);
    }

    private static <T> SentinelFetch<T> toFetch(String operation, SentinelPortalResponseParser.Parsed<T> parsed, Data data) {
        if (parsed.nullValueCount() > 0 || parsed.pendingCount() > 0) {
            // 결측 · 진행 중인 칸의 개수만 남긴다. 값 · 본문은 남기지 않는다.
            log.info("Sentinel portal partial values. operation={} nullValues={} pending={}", operation, parsed.nullValueCount(), parsed.pendingCount());
        }
        log.info("Sentinel portal fetched. operation={} rows={} rawRows={} calls={} bytes={}",
            operation, parsed.rows().size(), parsed.rawRowCount(), Data.CALLS_PER_REQUEST, data.body().length);
        return new SentinelFetch<>(parsed.rows(), sha256(data.body()), data.body().length, parsed.rawRowCount(),
            Data.CALLS_PER_REQUEST, parsed.nullValueCount(), parsed.pendingCount());
    }

    private static String sha256(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (NoSuchAlgorithmException exception) {
            // 모든 JVM 이 SHA-256 을 갖춰야 한다 (MessageDigest 명세).
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    /** 요청 하나가 받은 데이터 응답. 해시 · 바이트 수는 데이터 응답 본문만 센다 (화면 응답은 쿠키만 쓰고 버린다). */
    private record Data(JsonNode value, byte[] body) {

        /** 요청 하나에 쓰는 HTTP 호출 수 — 화면 + 데이터. */
        private static final int CALLS_PER_REQUEST = 2;
    }

    /** 대기. 테스트가 실제로 자지 않도록 바꿔 끼운다. */
    interface Sleeper {

        void sleep(Duration duration) throws InterruptedException;
    }

    /** 호출 하나. 간격 · 실패 처리를 한곳에서 하려고 감싼다. */
    private interface RestCall<T> {

        ResponseEntity<T> execute();
    }
}
