package com.sneezecast.domainlayer.sentinelimport.adapter.out.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.sneezecast.domainlayer.official.domain.enums.OfficialAgeGroup;
import com.sneezecast.domainlayer.official.domain.model.KdcaWeek;
import com.sneezecast.domainlayer.sentinelimport.application.exception.SentinelImportErrorCode;
import com.sneezecast.domainlayer.sentinelimport.application.exception.SentinelImportException;
import com.sneezecast.domainlayer.sentinelimport.domain.model.SentinelIliRow;
import com.sneezecast.domainlayer.sentinelimport.domain.model.SentinelPathogenCatalog;
import com.sneezecast.domainlayer.sentinelimport.domain.model.SentinelPathogenRow;
import com.sneezecast.domainlayer.sentinelimport.domain.model.SentinelRequest;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 표본감시 화면 데이터 응답 해석 (data-api-analysis §3-3 · §3-4). 상태가 없어 정적 메서드만 둔다.
 *
 * <p><b>두 가지 실패를 가른다.</b> 열 구성({@code captionList} · {@code headerList} · 연령대 라벨)이 기대와 다르면 {@code SCHEMA_CHANGED} 다 — 열 순서로
 * 병원체 코드를 붙이므로 열이 바뀌면 값이 다른 병원체로 들어간다. 행 · 값(연도 · 주차 · 요청 범위 · 절기 · 중복 · 숫자 형식 · 컬럼 제약)이 어긋나면
 * {@code RESPONSE_INVALID} 다.
 *
 * <p>예외 메시지에는 원천이 준 짧은 값만 잘라 넣고 본문 전체나 파서 메시지(본문 일부를 인용한다)는 넣지 않는다.
 */
final class SentinelPortalResponseParser {

    /** 진행 중인 주는 값 대신 이 문자열이 온다. 결측({@code -})과 달리 아직 없는 값이라 행을 만들지 않는다. */
    static final String PENDING_VALUE = "집계 중";
    /** 화면이 음수 · 빈 값을 이 문자로 그린다 — 둘 다 결측으로 본다. */
    static final String MISSING_VALUE = "-";
    /** 절기 안의 순번({@code GR2})이 이 값을 넘으면 새해 이후의 주다 ({@code GR2 − 53} 주). */
    static final int SEASON_YEAR_BOUNDARY = 53;

    private static final String COLUMN_PREFIX = "COLUMN";
    private static final Pattern YEAR_PATTERN = Pattern.compile("\\d{4}");
    private static final Pattern WEEK_PATTERN = Pattern.compile("\\d{2}");
    private static final Pattern WEEK_TITLE_PATTERN = Pattern.compile("(\\d{1,2})주");
    private static final Pattern ORDER_PATTERN = Pattern.compile("\\d{1,3}");
    /**
     * 쉼표를 지우기 전의 값. 쉼표는 천 단위 자리에만 온다 ({@code 1,520} · {@code 1520}). {@code 12,34} · {@code 1,,5} · 지수 표기 · 부호
     * {@code +} 같은 다른 표기는 화면이 쓰지 않으므로 받지 않는다.
     */
    private static final Pattern NUMBER_PATTERN = Pattern.compile("-?(\\d{1,3}(,\\d{3})+|\\d+)(\\.\\d+)?");
    /** {@code COLUMN1} 부터. {@code COLUMN0} · {@code COLUMN01} 같은 키는 열 번호 규칙이 바뀐 것이다. */
    private static final Pattern COLUMN_FIELD_PATTERN = Pattern.compile("COLUMN([1-9]\\d{0,2})");
    /** 메시지에 싣는 원천 값의 최대 길이. 원천이 엉뚱한 긴 값을 주어도 메시지 · 이력이 부풀지 않게 자른다. */
    private static final int MAX_SOURCE_TEXT = 100;

    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder().build();

    private SentinelPortalResponseParser() {
    }

    /**
     * 공통 봉투 {@code {"result":true,"value":{…}}} 를 판정하고 {@code value} 를 꺼낸다. 포털 점검 화면(HTML)은 JSON 이 아니라 여기서 걸린다.
     */
    static JsonNode value(String operation, byte[] body) {
        JsonNode root;
        try {
            root = OBJECT_MAPPER.readTree(body);
        } catch (IOException exception) {
            throw invalid(operation, "not json");
        }
        if (root == null || !root.isObject()) {
            throw invalid(operation, "not json object");
        }
        JsonNode result = root.get("result");
        if (result == null || !result.isBoolean()) {
            throw invalid(operation, "result missing");
        }
        if (!result.booleanValue()) {
            throw invalid(operation, "result is false");
        }
        JsonNode value = root.get("value");
        if (value == null || !value.isObject()) {
            throw invalid(operation, "value missing");
        }
        return value;
    }

    /** 급성호흡기 · 장관감염증 — 행 = 주, 열 = 계 + 병원체. 한 칸이 한 행이 된다. */
    static Parsed<SentinelPathogenRow> pathogens(String operation, JsonNode value, SentinelRequest request) {
        List<SentinelPathogenCatalog.Column> columns = SentinelPathogenCatalog.columns(request.program());
        List<String> captions = SentinelPathogenCatalog.captions(request.program());
        requireSameList(operation, "captionList", captions, texts(operation, value, "captionList"));
        // captionList 는 화면 표시용이고 headerList 가 열의 정의다. 둘 중 하나만 바뀌어도 열 대응을 믿을 수 없다.
        requireSameList(operation, "headerList", captions, headerCaptions(operation, value));

        JsonNode data = requireArray(operation, value, "data");
        // 두 주 이상을 요청했는데 행이 없으면 원천 고장이다 — 최근 범위에는 공표된 주가 있다. 진행 중인 주 하나만 요청했으면 비어 있을 수 있다.
        if (data.isEmpty() && !request.from().equals(request.to())) {
            throw invalid(operation, "data is empty");
        }
        List<SentinelPathogenRow> rows = new ArrayList<>();
        Set<KdcaWeek> weeks = new HashSet<>();
        int nullValueCount = 0;
        int pendingCount = 0;
        for (JsonNode row : data) {
            requireObject(operation, row, "data row");
            KdcaWeek week = week(operation, row, request);
            requireNoExtraColumn(operation, row, columns.size());
            // 같은 주가 두 번 오면 어느 쪽이 맞는지 알 수 없다. 접지 않고 실패한다.
            if (!weeks.add(week)) {
                throw invalid(operation, "duplicate week %d-%02d".formatted(week.year(), week.week()));
            }
            for (int index = 0; index < columns.size(); index++) {
                SentinelPathogenCatalog.Column column = columns.get(index);
                Cell cell = cell(operation, row, index + 1);
                if (cell.pending()) {
                    pendingCount++;
                    continue;
                }
                if (cell.value() == null) {
                    nullValueCount++;
                }
                rows.add(pathogenRow(operation, week, column, cell.value()));
            }
        }
        return new Parsed<>(rows, data.size(), nullValueCount, pendingCount);
    }

    /**
     * 인플루엔자 — 행 = 연령대 7개, 열 = 절기의 주. 한 칸이 한 행이 된다.
     *
     * <p>아직 시작하지 않은 절기는 {@code headerList} · {@code captionList} 가 둘 다 비고 행에 {@code COLUMN} 이 하나도 없다 (2026-10-06 실호출,
     * 2027–2028 절기). 절기 첫 주 실행이 늘 실패하지 않도록 0행으로 받는다. 연령대 라벨 검사는 그대로 하고, 한쪽만 비었거나 행에 {@code COLUMN}
     * 이 있으면 형식 변경이다.
     */
    static Parsed<SentinelIliRow> influenza(String operation, JsonNode value, SentinelRequest request) {
        List<KdcaWeek> weeks = weeks(operation, value, request);
        List<String> labels = SentinelPathogenCatalog.ageLabels();
        JsonNode data = requireArray(operation, value, "data");
        if (data.size() != labels.size()) {
            throw schemaChanged(operation, "data rows expected=%d actual=%d".formatted(labels.size(), data.size()));
        }

        List<SentinelIliRow> rows = new ArrayList<>();
        Set<OfficialAgeGroup> seen = new HashSet<>();
        int nullValueCount = 0;
        int pendingCount = 0;
        for (JsonNode row : data) {
            requireObject(operation, row, "data row");
            String label = requiredText(operation, row, "TITLE", true);
            OfficialAgeGroup ageGroup = SentinelPathogenCatalog.ageGroup(label);
            if (ageGroup == null) {
                throw schemaChanged(operation, "unexpected age label " + clip(label));
            }
            if (!seen.add(ageGroup)) {
                throw schemaChanged(operation, "duplicate age label " + clip(label));
            }
            requireNoExtraColumn(operation, row, weeks.size());
            for (int index = 0; index < weeks.size(); index++) {
                Cell cell = cell(operation, row, index + 1);
                if (cell.pending()) {
                    // 진행 중인 주 — 아직 없는 값이라 행을 만들지 않는다.
                    pendingCount++;
                    continue;
                }
                if (cell.value() == null) {
                    nullValueCount++;
                }
                rows.add(iliRow(operation, weeks.get(index), ageGroup, cell.value()));
            }
        }
        return new Parsed<>(rows, data.size(), nullValueCount, pendingCount);
    }

    static SentinelImportException invalid(String operation, String reason) {
        return new SentinelImportException(SentinelImportErrorCode.RESPONSE_INVALID, operation, reason);
    }

    static SentinelImportException schemaChanged(String operation, String reason) {
        return new SentinelImportException(SentinelImportErrorCode.SCHEMA_CHANGED, operation, reason);
    }

    /**
     * 열 제목({@code headerList[].GR2} · {@code TITLE})에서 절기의 주를 읽는다. {@code GR2} 가 절기 안의 순번이라
     * {@value #SEASON_YEAR_BOUNDARY} 이하면 시작 연도, 넘으면 끝 연도의 {@code GR2 − 53} 주다. 열 수는 절기마다 다를 수 있어
     * ({@code 53주} 가 있는 해) 세지 않는다.
     */
    private static List<KdcaWeek> weeks(String operation, JsonNode value, SentinelRequest request) {
        JsonNode headerList = requireArray(operation, value, "headerList");
        List<KdcaWeek> weeks = new ArrayList<>();
        List<String> titles = new ArrayList<>();
        Set<KdcaWeek> seen = new HashSet<>();
        for (JsonNode header : headerList) {
            requireObject(operation, header, "headerList item");
            String order = requiredText(operation, header, "GR2", true);
            String title = requiredText(operation, header, "TITLE", true);
            if (!ORDER_PATTERN.matcher(order).matches()) {
                throw schemaChanged(operation, "unexpected GR2 " + clip(order));
            }
            int seasonOrder = Integer.parseInt(order);
            boolean afterNewYear = seasonOrder > SEASON_YEAR_BOUNDARY;
            int week = afterNewYear ? seasonOrder - SEASON_YEAR_BOUNDARY : seasonOrder;
            Matcher matcher = WEEK_TITLE_PATTERN.matcher(title);
            if (!matcher.matches() || Integer.parseInt(matcher.group(1)) != week) {
                throw schemaChanged(operation, "GR2 %s does not match TITLE %s".formatted(clip(order), clip(title)));
            }
            KdcaWeek kdcaWeek = headerWeek(operation, afterNewYear ? request.seasonEndYear() : request.seasonStartYear(), week);
            // 절기는 시작 연도 36주 ~ 끝 연도 35주다. 그 밖의 주가 오면 연도를 잘못 붙이게 된다.
            boolean inSeason = afterNewYear ? week < SentinelRequest.SEASON_START_WEEK : week >= SentinelRequest.SEASON_START_WEEK;
            if (!inSeason) {
                throw invalid(operation, "week out of season %d-%02d".formatted(kdcaWeek.year(), kdcaWeek.week()));
            }
            if (!seen.add(kdcaWeek)) {
                throw invalid(operation, "duplicate week %d-%02d".formatted(kdcaWeek.year(), kdcaWeek.week()));
            }
            weeks.add(kdcaWeek);
            titles.add(title);
        }
        requireSameList(operation, "captionList", titles, texts(operation, value, "captionList"));
        return weeks;
    }

    /** {@code data[]} 한 행의 주 ({@code TITLE} = 네 자리 연도, {@code SUBTITLE} = 두 자리 주차). 요청 범위 밖이면 실패한다. */
    private static KdcaWeek week(String operation, JsonNode row, SentinelRequest request) {
        String year = requiredText(operation, row, "TITLE", false);
        String week = requiredText(operation, row, "SUBTITLE", false);
        if (!YEAR_PATTERN.matcher(year).matches() || !WEEK_PATTERN.matcher(week).matches()) {
            throw invalid(operation, "unexpected week %s-%s".formatted(clip(year), clip(week)));
        }
        KdcaWeek kdcaWeek;
        try {
            // 53주가 없는 해의 53주는 여기서 걸린다.
            kdcaWeek = new KdcaWeek(Integer.parseInt(year), Integer.parseInt(week));
        } catch (IllegalArgumentException exception) {
            throw invalid(operation, "unexpected week %s-%s".formatted(clip(year), clip(week)));
        }
        if (kdcaWeek.start().isBefore(request.from().start()) || kdcaWeek.start().isAfter(request.to().start())) {
            throw invalid(operation, "week out of range %d-%02d".formatted(kdcaWeek.year(), kdcaWeek.week()));
        }
        return kdcaWeek;
    }

    /** 53주가 없는 해의 53주 · 0주는 여기서 걸린다. */
    private static KdcaWeek headerWeek(String operation, int year, int week) {
        try {
            return new KdcaWeek(year, week);
        } catch (IllegalArgumentException exception) {
            throw invalid(operation, "unexpected week %d-%02d".formatted(year, week));
        }
    }

    /**
     * 행의 {@code COLUMN} 키는 {@code COLUMN1} ~ {@code COLUMN{n}} 뿐이어야 한다. 열 제목보다 많거나 번호 규칙이 다르면({@code COLUMN0} ·
     * {@code COLUMN01}) 열 구성이 바뀐 것이다. 남는 칸을 버리지 않고 실패한다.
     */
    private static void requireNoExtraColumn(String operation, JsonNode row, int columnCount) {
        for (String field : (Iterable<String>) row::fieldNames) {
            if (!field.startsWith(COLUMN_PREFIX)) {
                continue;
            }
            Matcher matcher = COLUMN_FIELD_PATTERN.matcher(field);
            if (!matcher.matches() || Integer.parseInt(matcher.group(1)) > columnCount) {
                throw schemaChanged(operation, "unexpected column %s columns=%d".formatted(clip(field), columnCount));
            }
        }
    }

    /** {@code headerList[i]} 를 {@code captionList} 와 같은 {@code "TITLE SUBTITLE"} 로 잇는다. */
    private static List<String> headerCaptions(String operation, JsonNode value) {
        JsonNode headerList = requireArray(operation, value, "headerList");
        List<String> captions = new ArrayList<>(headerList.size());
        for (JsonNode header : headerList) {
            requireObject(operation, header, "headerList item");
            captions.add(requiredText(operation, header, "TITLE", true) + " " + requiredText(operation, header, "SUBTITLE", true));
        }
        return captions;
    }

    private static SentinelPathogenRow pathogenRow(String operation, KdcaWeek week, SentinelPathogenCatalog.Column column, BigDecimal value) {
        try {
            return new SentinelPathogenRow(week.year(), week.week(), column.diseaseKey(), column.diseaseName(), column.diseaseGroup(), value);
        } catch (IllegalArgumentException exception) {
            throw invalid(operation, exception.getMessage());
        }
    }

    private static SentinelIliRow iliRow(String operation, KdcaWeek week, OfficialAgeGroup ageGroup, BigDecimal value) {
        try {
            return new SentinelIliRow(week.year(), week.week(), ageGroup, value);
        } catch (IllegalArgumentException exception) {
            throw invalid(operation, exception.getMessage());
        }
    }

    /**
     * {@code COLUMN{n}} 한 칸. 천 단위 쉼표를 지우고 숫자로 바꾼다. {@code 집계 중} 은 아직 없는 값이고, {@code -} · 빈 값 · 음수는 결측
     * (화면이 셋 다 {@code -} 로 그린다). 그 밖의 문자는 해석하지 않고 실패한다.
     */
    private static Cell cell(String operation, JsonNode row, int column) {
        String field = COLUMN_PREFIX + column;
        JsonNode node = row.get(field);
        if (node == null || node.isNull() || !node.isValueNode()) {
            throw schemaChanged(operation, field + " missing");
        }
        String raw = node.asText().trim();
        if (PENDING_VALUE.equals(raw)) {
            return new Cell(null, true);
        }
        if (raw.isEmpty() || MISSING_VALUE.equals(raw)) {
            return new Cell(null, false);
        }
        if (!NUMBER_PATTERN.matcher(raw).matches()) {
            throw invalid(operation, "unexpected value " + clip(raw));
        }
        BigDecimal value = new BigDecimal(raw.replace(",", ""));
        return new Cell(value.signum() < 0 ? null : value, false);
    }

    /** 기대 목록과 정확히 같아야 한다. 다르면 첫 위치 · 기대값 · 실제값만 짧게 싣는다. */
    private static void requireSameList(String operation, String field, List<String> expected, List<String> actual) {
        for (int index = 0; index < Math.max(expected.size(), actual.size()); index++) {
            String want = index < expected.size() ? expected.get(index) : null;
            String got = index < actual.size() ? actual.get(index) : null;
            if (!Objects.equals(want, got)) {
                throw schemaChanged(operation, "%s[%d] expected=%s actual=%s size expected=%d actual=%d"
                    .formatted(field, index, clip(String.valueOf(want)), clip(String.valueOf(got)), expected.size(), actual.size()));
            }
        }
    }

    private static List<String> texts(String operation, JsonNode value, String field) {
        JsonNode array = requireArray(operation, value, field);
        List<String> texts = new ArrayList<>(array.size());
        for (JsonNode item : array) {
            if (!item.isTextual()) {
                throw schemaChanged(operation, field + " has a non-text item");
            }
            texts.add(item.asText().trim());
        }
        return texts;
    }

    private static JsonNode requireArray(String operation, JsonNode value, String field) {
        JsonNode node = value.get(field);
        if (node == null || !node.isArray()) {
            throw schemaChanged(operation, field + " is not an array");
        }
        return node;
    }

    private static void requireObject(String operation, JsonNode node, String what) {
        if (!node.isObject()) {
            throw schemaChanged(operation, what + " is not an object");
        }
    }

    /** 값 노드의 문자열 (앞뒤 공백만 제거). 비면 실패이고, 열 구성에 속한 값이면 {@code SCHEMA_CHANGED} 다. */
    private static String requiredText(String operation, JsonNode node, String field, boolean schema) {
        JsonNode value = node.get(field);
        if (value == null || !value.isValueNode() || value.isNull() || value.asText().isBlank()) {
            throw schema ? schemaChanged(operation, field + " missing") : invalid(operation, field + " missing");
        }
        return value.asText().trim();
    }

    /** 메시지에 싣는 원천 값을 자른다. 데이터에는 쓰지 않는다. */
    private static String clip(String text) {
        return text.length() <= MAX_SOURCE_TEXT ? text : text.substring(0, MAX_SOURCE_TEXT) + "...";
    }

    /**
     * 해석 결과.
     *
     * @param rows           만든 행
     * @param rawRowCount    응답 {@code data} 의 행 수
     * @param nullValueCount 값이 결측인 행 수
     * @param pendingCount   {@code 집계 중} 이라 행을 만들지 않은 칸 수
     * @param <T>            행 타입
     */
    record Parsed<T>(List<T> rows, int rawRowCount, int nullValueCount, int pendingCount) {

    }

    /**
     * 칸 하나의 값.
     *
     * @param value   숫자 값. 결측이면 null
     * @param pending {@code 집계 중} 인지
     */
    private record Cell(BigDecimal value, boolean pending) {

    }
}
