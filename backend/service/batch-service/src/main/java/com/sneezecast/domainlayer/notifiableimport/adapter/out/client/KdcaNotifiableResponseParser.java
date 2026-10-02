package com.sneezecast.domainlayer.notifiableimport.adapter.out.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.sneezecast.domainlayer.notifiableimport.application.exception.NotifiableImportErrorCode;
import com.sneezecast.domainlayer.notifiableimport.application.exception.NotifiableImportException;
import com.sneezecast.domainlayer.notifiableimport.domain.model.NotifiableRegionRow;
import com.sneezecast.domainlayer.notifiableimport.domain.model.NotifiableRequest;
import com.sneezecast.domainlayer.notifiableimport.domain.model.NotifiableWeeklyRow;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 전수신고 API 응답 해석 (data-api-analysis §2-1 · §2-2). 상태가 없어 정적 메서드만 둔다.
 *
 * <p>예외 메시지에는 원천이 준 짧은 코드 · 값만 잘라서 넣고, 본문 전체나 파서 메시지(본문 일부를 인용한다)는 넣지 않는다.
 */
final class KdcaNotifiableResponseParser {

    static final String RESULT_CODE_SUCCESS = "00";
    /** 주 단위 응답에서 감염병마다 붙는 합계 행. 주별 값의 합인 파생값이라 버린다. */
    static final String TOTAL_PERIOD = "계";

    private static final Pattern WEEK_PERIOD = Pattern.compile("(\\d{4})년 (\\d{2})주");
    /** {@code /Region} 의 연도는 {@code 2026년} 이다. 접미사가 빠진 {@code 2026} 도 받는다. */
    private static final Pattern REGION_YEAR = Pattern.compile("(\\d{4})년?");
    private static final Pattern GROUP_WITHOUT_PREFIX = Pattern.compile("\\d+급");
    private static final Pattern RETURN_REASON_CODE = Pattern.compile("<returnReasonCode>\\s*([^<]*?)\\s*</returnReasonCode>");
    private static final Pattern RETURN_AUTH_MSG = Pattern.compile("<returnAuthMsg>\\s*([^<]*?)\\s*</returnAuthMsg>");
    private static final List<String> GATEWAY_MARKERS = List.of("OpenAPI_ServiceResponse", "cmmMsgHeader");
    private static final String UNKNOWN = "unknown";
    private static final String DISEASE_MARK = "@";
    /** 메시지에 싣는 원천 값의 최대 길이. 원천이 엉뚱한 긴 값을 주어도 메시지 · 이력이 부풀지 않게 자른다. */
    private static final int MAX_SOURCE_TEXT = 100;
    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder().build();

    private KdcaNotifiableResponseParser() {
    }

    /** 앞 공백 · UTF-8 BOM 을 건너뛰고 {@code <} 로 시작하면 마크업(XML · HTML)으로 본다. 게이트웨이 봉투인지는 {@link #isGatewayEnvelope} 가 가른다. */
    static boolean looksLikeMarkup(byte[] body) {
        if (body == null) {
            return false;
        }
        int index = 0;
        if (body.length >= UTF8_BOM.length && body[0] == UTF8_BOM[0] && body[1] == UTF8_BOM[1] && body[2] == UTF8_BOM[2]) {
            index = UTF8_BOM.length;
        }
        while (index < body.length && isWhitespace(body[index])) {
            index++;
        }
        return index < body.length && body[index] == '<';
    }

    /**
     * 공공데이터포털 게이트웨이의 XML 봉투인지. 마크업 본문 중 {@code OpenAPI_ServiceResponse} · {@code cmmMsgHeader} 표식이 있을 때만이다 —
     * 프록시의 HTML 502 같은 본문을 인증키 · 트래픽 문제({@code KDCA_GATEWAY_REJECTED})로 오판하지 않게 한다.
     */
    static boolean isGatewayEnvelope(byte[] body) {
        if (!looksLikeMarkup(body)) {
            return false;
        }
        String text = new String(body, StandardCharsets.UTF_8);
        return GATEWAY_MARKERS.stream().anyMatch(text::contains);
    }

    /** XML 봉투({@code OpenAPI_ServiceResponse.cmmMsgHeader})에서 거절 사유 코드 · 메시지만 뽑는다. 없으면 {@code unknown}. */
    static NotifiableImportException gatewayRejected(String operation, int httpStatus, byte[] body) {
        String xml = new String(body, StandardCharsets.UTF_8);
        return new NotifiableImportException(NotifiableImportErrorCode.KDCA_GATEWAY_REJECTED, operation, httpStatus,
            xmlField(RETURN_REASON_CODE, xml), xmlField(RETURN_AUTH_MSG, xml));
    }

    /**
     * JSON 봉투를 판정하고 한 페이지의 item 목록을 꺼낸다. HTTP 200 만 보고 성공으로 보지 않는다.
     * <ol>
     *   <li>{@code response} 감싸개 없이 최상위 {@code header.resultCode} 가 있으면 파라미터 오류다 (예: {@code 104}).</li>
     *   <li>{@code response.header.resultCode} 가 {@code 00} 이 아니면 오류다.</li>
     *   <li>{@code body.items.item} 은 1건이어도 배열이고, 결과가 없으면 {@code []} 다.</li>
     * </ol>
     */
    static Page parsePage(String operation, byte[] body) {
        JsonNode root;
        try {
            root = OBJECT_MAPPER.readTree(body);
        } catch (IOException exception) {
            throw invalid(operation, "not json");
        }
        if (root == null || !root.isObject()) {
            throw invalid(operation, "not json object");
        }
        JsonNode response = root.get("response");
        if (response == null || response.isNull()) {
            String resultCode = optionalText(root.path("header").get("resultCode"));
            if (resultCode != null) {
                throw apiError(operation, resultCode, root.path("header"));
            }
            throw invalid(operation, "response missing");
        }
        if (!response.isObject()) {
            throw invalid(operation, "response is not an object");
        }
        String resultCode = optionalText(response.path("header").get("resultCode"));
        if (resultCode == null) {
            throw invalid(operation, "resultCode missing");
        }
        if (!RESULT_CODE_SUCCESS.equals(resultCode)) {
            throw apiError(operation, resultCode, response.path("header"));
        }
        JsonNode bodyNode = response.get("body");
        if (bodyNode == null || !bodyNode.isObject()) {
            throw invalid(operation, "body missing");
        }
        int totalCount = totalCount(operation, bodyNode.get("totalCount"));
        JsonNode itemNode = bodyNode.path("items").get("item");
        if (itemNode == null || !itemNode.isArray()) {
            throw invalid(operation, "items.item is not an array");
        }
        List<JsonNode> items = new ArrayList<>(itemNode.size());
        for (JsonNode item : itemNode) {
            if (!item.isObject()) {
                throw invalid(operation, "item is not an object");
            }
            items.add(item);
        }
        return new Page(totalCount, items);
    }

    /** {@code /PeriodBasic} 주 단위 item 하나. {@code 계} 행이면 빈 값이다. */
    static Optional<NotifiableWeeklyRow> weeklyRow(String operation, int year, JsonNode item) {
        String period = requiredText(operation, item, "period");
        if (TOTAL_PERIOD.equals(period)) {
            return Optional.empty();
        }
        // 월(2026년 01월) · 연(2024년) 형식도 여기서 걸린다 — 주 단위로 요청했는데 다른 단위가 오면 조용히 넘기지 않는다.
        Matcher matcher = WEEK_PERIOD.matcher(period);
        if (!matcher.matches()) {
            throw invalid(operation, "unexpected period " + clip(period));
        }
        int periodYear = Integer.parseInt(matcher.group(1));
        int periodWeek = Integer.parseInt(matcher.group(2));
        if (periodYear != year) {
            throw invalid(operation, "period year mismatch " + clip(period));
        }
        if (periodWeek < NotifiableWeeklyRow.MIN_WEEK || periodWeek > NotifiableWeeklyRow.MAX_WEEK) {
            throw invalid(operation, "period week out of range " + clip(period));
        }
        Disease disease = disease(operation, item);
        try {
            return Optional.of(new NotifiableWeeklyRow(periodYear, periodWeek, disease.key(), disease.name(), disease.group(), disease.value()));
        } catch (IllegalArgumentException exception) {
            throw invalid(operation, exception.getMessage());
        }
    }

    /** {@code /Region} item 하나. 전국 행({@code sidoCd 00})이면 빈 값이고, 요청 시도도 전국도 아닌 코드면 실패한다. */
    static Optional<NotifiableRegionRow> regionRow(String operation, int year, String sidoCode, JsonNode item) {
        String yearText = requiredText(operation, item, "year");
        Matcher matcher = REGION_YEAR.matcher(yearText);
        if (!matcher.matches()) {
            throw invalid(operation, "unexpected year " + clip(yearText));
        }
        if (Integer.parseInt(matcher.group(1)) != year) {
            throw invalid(operation, "year mismatch " + clip(yearText));
        }
        // 이름은 출처마다 표기가 달라(광주 · 광주 (현재 미사용)) 판정은 코드로만 한다.
        String rowSidoCode = requiredText(operation, item, "sidoCd");
        if (NotifiableRequest.NATION_SIDO_CODE.equals(rowSidoCode)) {
            return Optional.empty();
        }
        if (!rowSidoCode.equals(sidoCode)) {
            throw invalid(operation, "unexpected sidoCd " + clip(rowSidoCode));
        }
        String sidoName = requiredText(operation, item, "sidoNm");
        Disease disease = disease(operation, item);
        try {
            return Optional.of(new NotifiableRegionRow(year, rowSidoCode, sidoName, disease.key(), disease.name(), disease.group(), disease.value()));
        } catch (IllegalArgumentException exception) {
            throw invalid(operation, exception.getMessage());
        }
    }

    static NotifiableImportException invalid(String operation, String reason) {
        return new NotifiableImportException(NotifiableImportErrorCode.KDCA_RESPONSE_INVALID, operation, reason);
    }

    private static NotifiableImportException apiError(String operation, String resultCode, JsonNode header) {
        String resultMsg = optionalText(header.get("resultMsg"));
        return new NotifiableImportException(NotifiableImportErrorCode.KDCA_API_ERROR, operation, clip(resultCode), resultMsg == null ? UNKNOWN : clip(resultMsg));
    }

    private static Disease disease(String operation, JsonNode item) {
        String name = requiredText(operation, item, "icdNm");
        // @니파바이러스감염증 · @엠폭스 — 표식의 의미는 원천 설명에 없다. 표식이 빠져도 같은 키가 되게 뗀다.
        String key = name;
        while (key.startsWith(DISEASE_MARK)) {
            key = key.substring(DISEASE_MARK.length());
        }
        key = key.trim();
        if (key.isEmpty()) {
            throw invalid(operation, "icdNm has no name " + clip(name));
        }
        return new Disease(key, name, diseaseGroup(optionalText(item.get("icdGroupNm"))), value(item.get("resultVal")));
    }

    /** {@code 1급} → {@code 제1급}. {@code 제1급} 은 그대로, 비면 null, 그 밖의 표기는 원천 그대로 둔다. */
    private static String diseaseGroup(String group) {
        if (group == null) {
            return null;
        }
        return GROUP_WITHOUT_PREFIX.matcher(group).matches() ? "제" + group : group;
    }

    /** 천 단위 쉼표를 지우고 숫자로. 비거나 숫자가 아니면 null (0 과 구분한다). */
    private static BigDecimal value(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.decimalValue();
        }
        if (!node.isTextual()) {
            return null;
        }
        String text = node.asText().replace(",", "").trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    /** {@code totalCount} 는 문자열({@code "2747"})로 온다. 숫자도 받는다. */
    private static int totalCount(String operation, JsonNode node) {
        int count;
        if (node != null && node.isIntegralNumber() && node.canConvertToInt()) {
            count = node.intValue();
        } else if (node != null && node.isTextual()) {
            try {
                count = Integer.parseInt(node.asText().trim());
            } catch (NumberFormatException exception) {
                throw invalid(operation, "totalCount missing or not a number");
            }
        } else {
            throw invalid(operation, "totalCount missing or not a number");
        }
        if (count < 0) {
            throw invalid(operation, "totalCount is negative");
        }
        return count;
    }

    private static String requiredText(String operation, JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isValueNode() || value.isNull() || value.asText().isBlank()) {
            throw invalid(operation, field + " missing");
        }
        return value.asText().trim();
    }

    /**
     * 값 노드의 문자열 (앞뒤 공백만 제거). 없거나 비면 null. 데이터로 쓰는 값이라 자르지 않는다 — 컬럼 길이 초과는 행 record 가 거절한다.
     * 메시지에 실을 때만 {@link #clip} 한다.
     */
    private static String optionalText(JsonNode node) {
        if (node == null || node.isNull() || !node.isValueNode()) {
            return null;
        }
        String text = node.asText().trim();
        return text.isEmpty() ? null : text;
    }

    private static String xmlField(Pattern pattern, String xml) {
        Matcher matcher = pattern.matcher(xml);
        if (!matcher.find() || matcher.group(1).isBlank()) {
            return UNKNOWN;
        }
        return clip(matcher.group(1));
    }

    /** 메시지에 싣는 원천 값을 자른다. 데이터에는 쓰지 않는다. */
    static String clip(String text) {
        return text.length() <= MAX_SOURCE_TEXT ? text : text.substring(0, MAX_SOURCE_TEXT) + "...";
    }

    private static boolean isWhitespace(byte value) {
        return value == ' ' || value == '\t' || value == '\r' || value == '\n';
    }

    /**
     * @param totalCount 원천이 알려 준 전체 item 수 (모든 페이지 합)
     * @param items      이 페이지의 item
     */
    record Page(int totalCount, List<JsonNode> items) {

    }

    private record Disease(String key, String name, String group, BigDecimal value) {

    }
}
