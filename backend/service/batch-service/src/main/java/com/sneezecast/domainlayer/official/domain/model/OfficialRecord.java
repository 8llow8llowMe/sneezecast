package com.sneezecast.domainlayer.official.domain.model;

import com.sneezecast.domainlayer.official.domain.enums.OfficialAgeGroup;
import com.sneezecast.domainlayer.official.domain.enums.OfficialMetric;
import com.sneezecast.domainlayer.official.domain.enums.OfficialPeriodType;
import com.sneezecast.domainlayer.official.domain.enums.OfficialProgram;
import com.sneezecast.domainlayer.official.domain.enums.OfficialRegionLevel;
import com.sneezecast.domainlayer.official.domain.enums.OfficialSource;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Month;
import java.util.Objects;

/**
 * upsert 할 {@code official_surveillance} 한 행 (entity-design §3-2). 테이블 구조의 정본은 surveillance-service 의 {@code OfficialSurveillanceEntity} 다.
 *
 * <p>생성 시 컬럼 길이 · 기간 · 값 자릿수를 검사한다 — DB 가 자르거나 거절하기 전에, 원천 파서가 잘못 읽은 값을 행 단위로 드러낸다.
 * 어긋나면 {@link IllegalArgumentException} 이고 원천 어댑터가 응답 해석 실패로 바꾼다 ({@code ImportedDistrict} 와 같다).
 *
 * <p>PK {@code id} 는 들고 있지 않다. 쓸 때 Snowflake 로 할당하고, 이미 있는 자연키면 기존 행의 id 가 남는다.
 *
 * @param diseaseKey   정규화 키 (표본감시 병원체 코드 · {@code TOTAL} · {@code ILI}, 전수신고 감염병명)
 * @param diseaseName  표시 이름 (원천 그대로)
 * @param diseaseGroup 원천 분류. 없으면 null
 * @param regionCode   질병관리청 시도 코드. 전국이면 {@code 00}
 * @param periodWeek   원천 주차. YEAR 면 0
 * @param periodStart  기간 시작일. WEEK 는 {@link KdcaWeek#start()} 와 같아야 하고, YEAR 는 1월 1일
 * @param periodEnd    기간 종료일. WEEK 는 {@link KdcaWeek#end()} 와 같아야 하고, YEAR 는 12월 31일
 * @param metricValue  값. <b>원천이 빈 칸이면 null</b> (0 과 구분한다). DECIMAL(12,2) 에 들어가야 한다
 */
public record OfficialRecord(
    OfficialSource source,
    OfficialProgram program,
    String diseaseKey,
    String diseaseName,
    String diseaseGroup,
    OfficialMetric metric,
    OfficialAgeGroup ageGroup,
    OfficialRegionLevel regionLevel,
    String regionCode,
    String regionName,
    OfficialPeriodType periodType,
    int periodYear,
    int periodWeek,
    LocalDate periodStart,
    LocalDate periodEnd,
    BigDecimal metricValue
) {

    /** 전국 행의 {@code region_code}. */
    public static final String NATION_REGION_CODE = "00";

    private static final int DISEASE_KEY_MAX_LENGTH = 100;
    private static final int DISEASE_NAME_MAX_LENGTH = 100;
    private static final int DISEASE_GROUP_MAX_LENGTH = 20;
    private static final int REGION_CODE_MAX_LENGTH = 4;
    private static final int REGION_NAME_MAX_LENGTH = 30;
    private static final int MAX_WEEK = 53;
    /** {@code period_year} 는 SMALLINT 다. */
    private static final int MAX_YEAR = 9999;
    /** DECIMAL(12,2) — 소수 2자리, 정수부 10자리. */
    private static final int VALUE_SCALE = 2;
    private static final int VALUE_INTEGER_DIGITS = 10;

    public OfficialRecord {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(program, "program");
        Objects.requireNonNull(metric, "metric");
        Objects.requireNonNull(ageGroup, "ageGroup");
        Objects.requireNonNull(regionLevel, "regionLevel");
        Objects.requireNonNull(periodType, "periodType");
        Objects.requireNonNull(periodStart, "periodStart");
        Objects.requireNonNull(periodEnd, "periodEnd");
        requireText(diseaseKey, "diseaseKey", DISEASE_KEY_MAX_LENGTH, diseaseKey);
        requireText(diseaseName, "diseaseName", DISEASE_NAME_MAX_LENGTH, diseaseKey);
        if (diseaseGroup != null) {
            requireText(diseaseGroup, "diseaseGroup", DISEASE_GROUP_MAX_LENGTH, diseaseKey);
        }
        requireText(regionCode, "regionCode", REGION_CODE_MAX_LENGTH, diseaseKey);
        requireText(regionName, "regionName", REGION_NAME_MAX_LENGTH, diseaseKey);
        checkRegion(regionLevel, regionCode, diseaseKey);
        checkPeriod(periodType, periodYear, periodWeek, periodStart, periodEnd, diseaseKey);
        checkValue(metricValue, diseaseKey);
    }

    /** UK {@code uk_official_surveillance_natural_key} 의 10 컬럼. 한 적재 안에서 중복을 가르는 데 쓴다. */
    public NaturalKey naturalKey() {
        return new NaturalKey(source, program, diseaseKey, metric, ageGroup, regionLevel, regionCode, periodType, periodYear, periodWeek);
    }

    public record NaturalKey(
        OfficialSource source,
        OfficialProgram program,
        String diseaseKey,
        OfficialMetric metric,
        OfficialAgeGroup ageGroup,
        OfficialRegionLevel regionLevel,
        String regionCode,
        OfficialPeriodType periodType,
        int periodYear,
        int periodWeek
    ) {

    }

    private static void checkRegion(OfficialRegionLevel regionLevel, String regionCode, String diseaseKey) {
        boolean nationCode = NATION_REGION_CODE.equals(regionCode);
        if ((regionLevel == OfficialRegionLevel.NATION) != nationCode) {
            throw new IllegalArgumentException("regionCode must be %s only for NATION. regionLevel=%s regionCode=%s diseaseKey=%s"
                .formatted(NATION_REGION_CODE, regionLevel, regionCode, diseaseKey));
        }
    }

    private static void checkPeriod(OfficialPeriodType periodType, int periodYear, int periodWeek, LocalDate periodStart, LocalDate periodEnd,
        String diseaseKey) {
        if (periodYear < 1 || periodYear > MAX_YEAR) {
            throw new IllegalArgumentException("periodYear must be 1..%d. periodYear=%d diseaseKey=%s".formatted(MAX_YEAR, periodYear, diseaseKey));
        }
        if (periodEnd.isBefore(periodStart)) {
            throw new IllegalArgumentException("periodEnd must not be before periodStart. periodStart=%s periodEnd=%s diseaseKey=%s"
                .formatted(periodStart, periodEnd, diseaseKey));
        }
        if (periodType == OfficialPeriodType.WEEK) {
            if (periodWeek < 1 || periodWeek > MAX_WEEK) {
                throw new IllegalArgumentException("WEEK periodWeek must be 1..%d. periodWeek=%d diseaseKey=%s"
                    .formatted(MAX_WEEK, periodWeek, diseaseKey));
            }
            // 그 해 주 수를 넘는 주차(52주인 해의 53주)는 KdcaWeek 생성에서 걸린다.
            KdcaWeek kdcaWeek = new KdcaWeek(periodYear, periodWeek);
            if (!periodStart.equals(kdcaWeek.start()) || !periodEnd.equals(kdcaWeek.end())) {
                throw new IllegalArgumentException("WEEK period must be KDCA week %d-%02d (%s ~ %s). periodStart=%s periodEnd=%s diseaseKey=%s"
                    .formatted(periodYear, periodWeek, kdcaWeek.start(), kdcaWeek.end(), periodStart, periodEnd, diseaseKey));
            }
            return;
        }
        if (periodWeek != 0
            || !periodStart.equals(LocalDate.of(periodYear, Month.JANUARY, 1))
            || !periodEnd.equals(LocalDate.of(periodYear, Month.DECEMBER, 31))) {
            throw new IllegalArgumentException("YEAR must be week 0 and %d-01-01 ~ %d-12-31. periodWeek=%d periodStart=%s periodEnd=%s diseaseKey=%s"
                .formatted(periodYear, periodYear, periodWeek, periodStart, periodEnd, diseaseKey));
        }
    }

    private static void checkValue(BigDecimal metricValue, String diseaseKey) {
        if (metricValue == null) {
            return;
        }
        // 1.50 처럼 끝자리 0 으로 늘어난 scale 은 값이 같으므로 허용한다.
        BigDecimal normalized = metricValue.stripTrailingZeros();
        int integerDigits = Math.max(normalized.precision() - normalized.scale(), 0);
        if (normalized.scale() > VALUE_SCALE || integerDigits > VALUE_INTEGER_DIGITS) {
            throw new IllegalArgumentException("metricValue must fit DECIMAL(12,2). metricValue=%s diseaseKey=%s"
                .formatted(metricValue.toPlainString(), diseaseKey));
        }
    }

    private static void requireText(String value, String field, int maxLength, String diseaseKey) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("%s must not be blank. diseaseKey=%s".formatted(field, diseaseKey));
        }
        // VARCHAR(n) 는 문자 수다. 한글은 BMP 라 length() 와 같지만 보조 평면 문자까지 맞춘다.
        if (value.codePointCount(0, value.length()) > maxLength) {
            throw new IllegalArgumentException("%s must be at most %d characters. length=%d diseaseKey=%s"
                .formatted(field, maxLength, value.length(), diseaseKey));
        }
    }
}
