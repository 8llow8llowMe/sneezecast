package com.sneezecast.domainlayer.districtimport.adapter.out.client;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.sneezecast.domainlayer.districtimport.application.exception.DistrictImportErrorCode;
import com.sneezecast.domainlayer.districtimport.application.exception.DistrictImportException;
import com.sneezecast.domainlayer.districtimport.application.port.out.DistrictSourcePort;
import com.sneezecast.domainlayer.districtimport.domain.model.DistrictSnapshot;
import com.sneezecast.domainlayer.districtimport.domain.model.ImportedDistrict;
import com.sneezecast.global.properties.SgisProperties;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriBuilder;

/**
 * SGIS 오픈API 로 기준 연도의 전국 읍면동 스냅샷을 만든다 (data-api-analysis §1).
 *
 * <p>흐름: 인증 1회 → 시도 목록({@code addr/stage.json}, cd 없음) → 시도마다 시군구 이름({@code addr/stage.json?cd=<시도>}) 과
 * 읍면동 경계({@code boundary/hadmarea.geojson?year=<year>&adm_cd=<시도>&low_search=2}). 실행당 약 36회이고 연 1회 수동이라 호출 사이에
 * 대기를 두지 않는다.
 *
 * <p><b>판정 규칙.</b> SGIS 는 실패도 대부분 HTTP 200 으로 돌려준다. 그래서 상태 코드가 아니라 본문의 {@code errCd}(0 = 성공)로 판정하고,
 * {@code errCd} 가 없으면 성공으로 보지 않는다. {@code -401}(토큰 만료 · 잘못된 토큰)이면 토큰을 다시 받아 <b>그 요청만 한 번</b> 재시도한다.
 *
 * <p><b>이름.</b> 경계의 {@code adm_nm} 은 전체 주소(예: {@code 서울특별시 송파구 가락1동})라 읍면동 이름은 마지막 토큰이다. 시군구 이름은 단계별
 * 주소의 {@code addr_name} 을 5자리 코드로 맞춘다. 단계별 주소에는 기준 연도 파라미터가 없어 최신 연도 기준이므로, 과거 연도에만 있던 시군구는
 * 못 찾을 수 있다 — 그때는 {@code adm_nm} 의 시도 다음 ~ 마지막 앞 토큰을 쓰고, 그것도 없으면(시군구가 없는 시도) 시도 이름을 쓴다.
 *
 * <p><b>비밀값.</b> 인증키와 토큰은 요청 URL 의 쿼리에 실린다. 토큰은 실행마다 새로 받고 저장 · 로그하지 않으며, URL 을 담은
 * {@link RestClientException} 은 예외 원인으로 붙이지 않는다. 경계 기하는 읽지 않고 건너뛴다 (시도 하나에 수 MB).
 */
@Slf4j
@Component
public class SgisDistrictSourceAdapter implements DistrictSourcePort {

    static final String AUTH_PATH = "/auth/authentication.json";
    static final String STAGE_PATH = "/addr/stage.json";
    static final String HADMAREA_PATH = "/boundary/hadmarea.geojson";

    private static final int ERR_CD_SUCCESS = 0;
    private static final int ERR_CD_TOKEN_INVALID = -401;
    /** 시도 코드 기준 두 단계 아래 = 읍면동. */
    private static final int LOW_SEARCH_EUPMYEONDONG = 2;
    private static final int SIDO_CODE_LENGTH = 2;
    private static final int SIGUNGU_CODE_LENGTH = 5;
    private static final int DISTRICT_CODE_LENGTH = 8;
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final String OPERATION_AUTH = "auth";

    /** 경계 응답은 기하가 대부분이다. 모르는 필드(geometry 등)는 트리로 만들지 않고 건너뛴다. */
    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder()
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build();

    private final RestClient restClient;
    private final SgisProperties properties;

    public SgisDistrictSourceAdapter(@Qualifier("sgisRestClient") RestClient restClient, SgisProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    @Override
    public DistrictSnapshot fetchSnapshot(int year) {
        if (!properties.hasCredentials()) {
            throw new DistrictImportException(DistrictImportErrorCode.SGIS_CREDENTIALS_MISSING);
        }
        Session session = new Session(authenticate());

        List<StageEntry> sidos = stageEntries("stage", SIDO_CODE_LENGTH,
            callAuthorized(session, "stage", (builder, token) -> builder.path(STAGE_PATH).queryParam("accessToken", "{token}").build(token),
                this::parseTree));

        List<String> sidoCodes = new ArrayList<>();
        List<ImportedDistrict> districts = new ArrayList<>();
        // 원천이 시도 단위로만 내려 주므로 시도마다 두 번 부른다 — 원천 구조상 반복이 불가피하다 (coding-conventions §8-5).
        for (StageEntry sido : sidos) {
            sidoCodes.add(sido.code());
            Map<String, String> sigunguNames = sigunguNames(session, sido);
            String operation = "hadmarea(adm_cd=%s)".formatted(sido.code());
            List<JsonNode> features = callAuthorized(session, operation, (builder, token) -> builder.path(HADMAREA_PATH)
                .queryParam("accessToken", "{token}")
                .queryParam("year", "{year}")
                .queryParam("adm_cd", "{admCd}")
                .queryParam("low_search", "{lowSearch}")
                .build(token, year, sido.code(), LOW_SEARCH_EUPMYEONDONG), this::parseHadmarea);
            for (JsonNode feature : features) {
                districts.add(toDistrict(operation, sido, sigunguNames, feature));
            }
            log.debug("SGIS sido fetched. year={} sidoCode={} districts={}", year, sido.code(), features.size());
        }

        log.info("SGIS district snapshot fetched. year={} sido={} districts={}", year, sidoCodes.size(), districts.size());
        return new DistrictSnapshot(year, sidoCodes, districts);
    }

    private String authenticate() {
        byte[] body = fetch(OPERATION_AUTH, true, builder -> builder.path(AUTH_PATH)
            .queryParam("consumer_key", "{consumerKey}")
            .queryParam("consumer_secret", "{consumerSecret}")
            .build(properties.consumerKey(), properties.consumerSecret()));
        Parsed<JsonNode> parsed = parseTree(OPERATION_AUTH, body);
        if (parsed.errCd() != ERR_CD_SUCCESS) {
            throw new DistrictImportException(DistrictImportErrorCode.SGIS_AUTH_FAILED, 200, parsed.errCd());
        }
        JsonNode accessToken = parsed.body().path("result").path("accessToken");
        if (!accessToken.isTextual() || accessToken.asText().isBlank()) {
            throw new DistrictImportException(DistrictImportErrorCode.SGIS_RESPONSE_INVALID, OPERATION_AUTH, "accessToken missing");
        }
        // accessTimeout(epoch 밀리초)은 쓰지 않는다 — 토큰이 4시간 유효하고 실행은 수십 초라 실행마다 새로 받는다.
        return accessToken.asText();
    }

    private Map<String, String> sigunguNames(Session session, StageEntry sido) {
        String operation = "stage(cd=%s)".formatted(sido.code());
        JsonNode root = callAuthorized(session, operation, (builder, token) -> builder.path(STAGE_PATH)
            .queryParam("accessToken", "{token}")
            .queryParam("cd", "{cd}")
            .build(token, sido.code()), this::parseTree);
        Map<String, String> names = new HashMap<>();
        for (StageEntry sigungu : stageEntries(operation, SIGUNGU_CODE_LENGTH, root)) {
            names.put(sigungu.code(), sigungu.name());
        }
        return names;
    }

    /**
     * 토큰이 필요한 호출. {@code errCd -401} 이면 토큰을 다시 받아 이 요청만 한 번 재시도하고, 그래도 -401 이면 실패한다.
     */
    private <T> T callAuthorized(Session session, String operation, BiFunction<UriBuilder, String, URI> uri, BiFunction<String, byte[], Parsed<T>> parser) {
        Parsed<T> parsed = parser.apply(operation, fetch(operation, false, builder -> uri.apply(builder, session.accessToken)));
        if (parsed.errCd() == ERR_CD_TOKEN_INVALID) {
            log.info("SGIS token rejected, re-authenticating once. operation={}", operation);
            session.accessToken = authenticate();
            parsed = parser.apply(operation, fetch(operation, false, builder -> uri.apply(builder, session.accessToken)));
            if (parsed.errCd() == ERR_CD_TOKEN_INVALID) {
                throw new DistrictImportException(DistrictImportErrorCode.SGIS_TOKEN_REJECTED, operation);
            }
        }
        if (parsed.errCd() != ERR_CD_SUCCESS) {
            throw new DistrictImportException(DistrictImportErrorCode.SGIS_API_ERROR, operation, parsed.errCd(), parsed.errMsg());
        }
        return parsed.body();
    }

    private byte[] fetch(String operation, boolean authRequest, Function<UriBuilder, URI> uri) {
        byte[] body;
        try {
            body = restClient.get().uri(uri).retrieve().body(byte[].class);
        } catch (RestClientResponseException exception) {
            // 인증 파라미터 누락은 412 + HTML 이다. 본문 · URL 은 싣지 않고 상태 코드만 남긴다.
            int status = exception.getStatusCode().value();
            if (authRequest) {
                throw new DistrictImportException(DistrictImportErrorCode.SGIS_AUTH_FAILED, status, "none");
            }
            throw new DistrictImportException(DistrictImportErrorCode.SGIS_HTTP_ERROR, operation, status);
        } catch (RestClientException exception) {
            // RestClientException 메시지에는 요청 URL(인증키 · 토큰 포함)이 실린다. 가장 안쪽 원인(I/O · timeout)만 붙인다.
            Throwable rootCause = NestedExceptionUtils.getMostSpecificCause(exception);
            Throwable cause = rootCause == exception ? null : rootCause;
            throw new DistrictImportException(DistrictImportErrorCode.SGIS_CALL_FAILED, cause, operation, rootCause.getClass().getSimpleName());
        }
        if (body == null || body.length == 0) {
            throw new DistrictImportException(DistrictImportErrorCode.SGIS_RESPONSE_INVALID, operation, "empty body");
        }
        return body;
    }

    private Parsed<JsonNode> parseTree(String operation, byte[] body) {
        JsonNode root;
        try {
            root = OBJECT_MAPPER.readTree(body);
        } catch (IOException exception) {
            // 인증 응답 본문에는 토큰이 있으므로 파서 메시지(본문 일부를 인용한다)를 싣지 않는다.
            throw new DistrictImportException(DistrictImportErrorCode.SGIS_RESPONSE_INVALID, operation, "not json");
        }
        if (root == null || !root.isObject()) {
            throw new DistrictImportException(DistrictImportErrorCode.SGIS_RESPONSE_INVALID, operation, "not json object");
        }
        return new Parsed<>(errCd(operation, root.get("errCd")), text(root.get("errMsg")), root);
    }

    private Parsed<List<JsonNode>> parseHadmarea(String operation, byte[] body) {
        HadmareaEnvelope envelope;
        try {
            envelope = OBJECT_MAPPER.readValue(body, HadmareaEnvelope.class);
        } catch (IOException exception) {
            throw new DistrictImportException(DistrictImportErrorCode.SGIS_RESPONSE_INVALID, operation, "not geojson");
        }
        if (envelope == null) {
            throw new DistrictImportException(DistrictImportErrorCode.SGIS_RESPONSE_INVALID, operation, "not geojson");
        }
        List<JsonNode> properties = new ArrayList<>();
        if (envelope.features() != null) {
            for (HadmareaFeature feature : envelope.features()) {
                if (feature == null || feature.properties() == null || !feature.properties().isObject()) {
                    throw new DistrictImportException(DistrictImportErrorCode.SGIS_RESPONSE_INVALID, operation, "feature without properties");
                }
                properties.add(feature.properties());
            }
        }
        return new Parsed<>(errCd(operation, envelope.errCd()), text(envelope.errMsg()), properties);
    }

    private static int errCd(String operation, JsonNode node) {
        if (node != null && node.canConvertToInt() && node.isIntegralNumber()) {
            return node.intValue();
        }
        if (node != null && node.isTextual()) {
            try {
                return Integer.parseInt(node.asText().trim());
            } catch (NumberFormatException ignored) {
                // 아래에서 응답 해석 실패로 처리한다.
            }
        }
        throw new DistrictImportException(DistrictImportErrorCode.SGIS_RESPONSE_INVALID, operation, "errCd missing or not a number");
    }

    private static List<StageEntry> stageEntries(String operation, int codeLength, JsonNode root) {
        JsonNode result = root.get("result");
        if (result == null || !result.isArray()) {
            throw new DistrictImportException(DistrictImportErrorCode.SGIS_RESPONSE_INVALID, operation, "result is not an array");
        }
        List<StageEntry> entries = new ArrayList<>();
        for (JsonNode entry : result) {
            String code = requiredText(operation, entry, "cd");
            if (code.length() != codeLength || !code.chars().allMatch(Character::isDigit)) {
                throw new DistrictImportException(DistrictImportErrorCode.SGIS_RESPONSE_INVALID, operation, "unexpected cd " + code);
            }
            entries.add(new StageEntry(code, requiredText(operation, entry, "addr_name")));
        }
        return entries;
    }

    private static ImportedDistrict toDistrict(String operation, StageEntry sido, Map<String, String> sigunguNames, JsonNode feature) {
        String code = requiredText(operation, feature, "adm_cd");
        if (code.length() != DISTRICT_CODE_LENGTH || !code.startsWith(sido.code())) {
            throw new DistrictImportException(DistrictImportErrorCode.SGIS_RESPONSE_INVALID, operation, "unexpected adm_cd " + code);
        }
        String[] tokens = WHITESPACE.split(requiredText(operation, feature, "adm_nm"));
        String sigunguCode = code.substring(0, SIGUNGU_CODE_LENGTH);
        String sigunguName = sigunguNames.get(sigunguCode);
        if (sigunguName == null) {
            sigunguName = tokens.length >= 3 ? String.join(" ", Arrays.copyOfRange(tokens, 1, tokens.length - 1)) : sido.name();
        }
        try {
            return new ImportedDistrict(code, tokens[tokens.length - 1], sido.code(), sido.name(), sigunguCode, sigunguName);
        } catch (IllegalArgumentException exception) {
            throw new DistrictImportException(DistrictImportErrorCode.SGIS_RESPONSE_INVALID, operation, exception.getMessage());
        }
    }

    private static String requiredText(String operation, JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || !value.isValueNode() || value.isNull() || value.asText().isBlank()) {
            throw new DistrictImportException(DistrictImportErrorCode.SGIS_RESPONSE_INVALID, operation, field + " missing");
        }
        return value.asText().trim();
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() ? "" : node.asText();
    }

    /** 실행 하나 동안만 사는 토큰 보관소. 어댑터는 싱글턴이라 필드에 토큰을 두지 않는다. */
    private static final class Session {

        private String accessToken;

        private Session(String accessToken) {
            this.accessToken = accessToken;
        }
    }

    private record Parsed<T>(int errCd, String errMsg, T body) {

    }

    private record StageEntry(String code, String name) {

    }

    private record HadmareaEnvelope(JsonNode errCd, JsonNode errMsg, List<HadmareaFeature> features) {

    }

    private record HadmareaFeature(JsonNode properties) {

    }
}
