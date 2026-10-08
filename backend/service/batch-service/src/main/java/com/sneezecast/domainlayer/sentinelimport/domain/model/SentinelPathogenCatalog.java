package com.sneezecast.domainlayer.sentinelimport.domain.model;

import com.sneezecast.domainlayer.official.domain.enums.OfficialAgeGroup;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 표본감시 화면 데이터의 열 목록 (data-api-analysis §3-3).
 *
 * <p><b>병원체 코드는 응답에 없다.</b> 포털 코드({@code subInfectious} 값)는 화면 폼의 선택 목록에만 있어서, 응답의 열 순서로 코드를 붙인다.
 * 그래서 {@code captionList} · {@code headerList} 대조가 곧 코드 대응의 보증이다 — 한 글자라도 다르면 적재하지 않고
 * {@code SentinelImportErrorCode.SCHEMA_CHANGED}({@code SENTINEL_IMPORT_005})로 실패한다.
 * 원천 표기 오타({@code 인를루엔자})도 있는 그대로 둔다.
 *
 * <p><b>설정이 아니라 코드 상수로 둔다.</b> 열이 바뀌면 코드 대응과 파싱도 함께 손봐야 하므로 설정만 고쳐 넘길 수 있는 값이 아니다. 형식 변경은
 * 배포가 필요한 사건이라 운영자가 설정으로 덮어쓰지 못하게 한다.
 */
public final class SentinelPathogenCatalog {

    /** 합계 열의 {@code disease_key} (entity-design §3-2). 원천 코드가 아니라 우리가 정한 값이다. */
    public static final String TOTAL_KEY = "TOTAL";

    private static final List<Column> ARI_COLUMNS = List.of(
        new Column(TOTAL_KEY, "계", "계"),
        new Column("ND0708", "세균", "마이코플라즈마균"),
        new Column("ND0709", "세균", "클라미디아균"),
        new Column("ND0701", "바이러스", "아데노바이러스"),
        new Column("ND0702", "바이러스", "사람 보카바이러스"),
        new Column("ND0703", "바이러스", "파라인플루엔자바이러스"),
        new Column("ND0704", "바이러스", "호흡기세포융합바이러스"),
        new Column("ND0705", "바이러스", "리노바이러스"),
        new Column("ND0706", "바이러스", "사람 메타뉴모바이러스"),
        new Column("ND0707", "바이러스", "사람 코로나바이러스"),
        // 원천 분류의 오타다 (인플루엔자가 아니라 인를루엔자). 고쳐 적으면 대조가 어긋난다.
        new Column("ND0001", "인를루엔자", "인플루엔자 바이러스"),
        new Column("ND0022", "코로나19", "코로나19 바이러스"));

    private static final List<Column> ENTERIC_COLUMNS = List.of(
        new Column(TOTAL_KEY, "계", "계"),
        new Column("ND0601", "세균", "살모넬라균"),
        new Column("ND0602", "세균", "장염비브리오균"),
        new Column("ND0603", "세균", "장독소성대장균(ETEC)"),
        new Column("ND0604", "세균", "장침습성대장균(EIEC)"),
        new Column("ND0605", "세균", "장병원성대장균(EPEC)"),
        new Column("ND0606", "세균", "캄필로박터균"),
        new Column("ND0607", "세균", "클로스트리듐 퍼프린젠스"),
        new Column("ND0608", "세균", "황색포도알균"),
        new Column("ND0609", "세균", "바실루스 세레우스균"),
        new Column("ND0610", "세균", "예르시니아 엔테로콜리티카"),
        new Column("ND0611", "세균", "리스테리아 모노사이토제네스"),
        new Column("ND0612", "바이러스", "그룹 A형 로타바이러스"),
        new Column("ND0613", "바이러스", "아스트로바이러스"),
        new Column("ND0614", "바이러스", "장내 아데노바이러스"),
        new Column("ND0615", "바이러스", "노로바이러스"),
        new Column("ND0616", "바이러스", "사포바이러스"),
        new Column("ND0617", "원충", "이질아메바"),
        new Column("ND0618", "원충", "람블편모충"),
        new Column("ND0619", "원충", "작은와포자충"),
        new Column("ND0620", "원충", "원포자충"));

    /** 인플루엔자 {@code data[].TITLE} 라벨 → 연령대. 7행이고 연령 전체 합계 행은 없다. */
    private static final Map<String, OfficialAgeGroup> AGE_GROUPS = ageGroups();

    private SentinelPathogenCatalog() {
    }

    /** 병원체별 신고 수 프로그램의 열 (응답 {@code COLUMN1} 부터의 순서). 인플루엔자는 열이 주차라 여기에 없다. */
    public static List<Column> columns(SentinelProgram program) {
        return switch (program) {
            case ARI -> ARI_COLUMNS;
            case ENTERIC -> ENTERIC_COLUMNS;
            case INFLUENZA_ILI -> throw new IllegalArgumentException("influenza columns are weeks, not pathogens. program=" + program);
        };
    }

    /** 기대하는 {@code captionList} = 열마다 {@code "분류 이름"}. */
    public static List<String> captions(SentinelProgram program) {
        return columns(program).stream().map(Column::caption).toList();
    }

    /** 인플루엔자 연령대 라벨 → 연령대. 모르는 라벨이면 null 이고, 원천 어댑터가 형식 변경으로 본다. */
    public static OfficialAgeGroup ageGroup(String label) {
        return AGE_GROUPS.get(label);
    }

    /** 인플루엔자 연령대 라벨 (원천 행 순서). */
    public static List<String> ageLabels() {
        return List.copyOf(AGE_GROUPS.keySet());
    }

    /** 라벨 순서가 원천 행 순서라 {@link Map#copyOf}(순서 미보장) 대신 {@link LinkedHashMap} 을 감싼다. */
    private static Map<String, OfficialAgeGroup> ageGroups() {
        Map<String, OfficialAgeGroup> labels = new LinkedHashMap<>();
        labels.put("0세", OfficialAgeGroup.AGE_0);
        labels.put("1-6세", OfficialAgeGroup.AGE_1_6);
        labels.put("7-12세", OfficialAgeGroup.AGE_7_12);
        labels.put("13-18세", OfficialAgeGroup.AGE_13_18);
        labels.put("19-49세", OfficialAgeGroup.AGE_19_49);
        labels.put("50-64세", OfficialAgeGroup.AGE_50_64);
        labels.put("65세 이상", OfficialAgeGroup.AGE_65_PLUS);
        return Collections.unmodifiableMap(labels);
    }

    /**
     * 열 하나.
     *
     * @param diseaseKey   포털 병원체 코드 (합계는 {@link #TOTAL_KEY})
     * @param diseaseGroup 원천 {@code headerList[].TITLE} 그대로 (세균 · 바이러스 · 원충 · 계 …)
     * @param diseaseName  원천 {@code headerList[].SUBTITLE} 그대로
     */
    public record Column(String diseaseKey, String diseaseGroup, String diseaseName) {

        /** 원천 {@code captionList[i]} 와 같은 문자열. */
        public String caption() {
            return diseaseGroup + " " + diseaseName;
        }
    }
}
