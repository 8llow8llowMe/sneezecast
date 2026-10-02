package com.sneezecast.domainlayer.notifiableimport.domain.model;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 전수신고 API 조회 조건 하나. 적재 이력({@code official_source_snapshot}) 한 행이 이 단위다 — 페이지를 넘긴 응답도 한 요청이다 (entity-design §3-3).
 *
 * <p>불변식: 연도는 네 자리(원천이 {@code 2026년} 으로 적는다). 주별 전국은 지표 · 시도가 없고, 시도 연별은 둘 다 있다. 시도 코드는 질병관리청
 * 코드 두 자리이며 {@code 00}(전국)은 받지 않는다 — {@code 00} 은 전국 행만 주므로 주별 전국과 겹친다 (data-api-analysis §2-4).
 * 어기면 {@link IllegalArgumentException} 이다.
 *
 * @param kind     조회 종류
 * @param year     원천 연도
 * @param measure  시도 연별의 지표. 주별 전국이면 null
 * @param sidoCode 시도 연별의 질병관리청 시도 코드. 주별 전국이면 null
 */
public record NotifiableRequest(Kind kind, int year, NotifiableRegionMeasure measure, String sidoCode) {

    public static final String NATION_SIDO_CODE = "00";

    private static final Pattern SIDO_CODE_PATTERN = Pattern.compile("\\d{2}");
    private static final int MIN_YEAR = 1000;
    private static final int MAX_YEAR = 9999;

    public NotifiableRequest {
        Objects.requireNonNull(kind, "kind must not be null");
        if (year < MIN_YEAR || year > MAX_YEAR) {
            throw new IllegalArgumentException("year must be 4 digits. year=" + year);
        }
        if (kind == Kind.PERIOD_BASIC_WEEKLY && (measure != null || sidoCode != null)) {
            throw new IllegalArgumentException("weekly nation request must not have measure or sidoCode. measure=%s sidoCode=%s".formatted(measure, sidoCode));
        }
        if (kind == Kind.REGION_YEARLY) {
            Objects.requireNonNull(measure, "measure must not be null for region request");
            requireSidoCode(sidoCode);
        }
    }

    /** 주별 전국 ({@code /PeriodBasic}, {@code searchPeriodType=3}). */
    public static NotifiableRequest weekly(int year) {
        return new NotifiableRequest(Kind.PERIOD_BASIC_WEEKLY, year, null, null);
    }

    /** 시도 연별 ({@code /Region}). */
    public static NotifiableRequest region(int year, NotifiableRegionMeasure measure, String sidoCode) {
        return new NotifiableRequest(Kind.REGION_YEARLY, year, measure, sidoCode);
    }

    /**
     * 적재 이력의 {@code request_key} (200자 이내). 조회 조건만 담고 인증키 · URL 은 넣지 않는다.
     * 예: {@code notifiable:periodBasic:week:2026}, {@code notifiable:region:count:2026:sido=01}.
     */
    public String requestKey() {
        return switch (kind) {
            case PERIOD_BASIC_WEEKLY -> "notifiable:periodBasic:week:%d".formatted(year);
            case REGION_YEARLY -> "notifiable:region:%s:%d:sido=%s".formatted(measure.getKeyName(), year, sidoCode);
        };
    }

    /** 질병관리청 시도 코드 두 자리이고 전국({@code 00})이 아니어야 한다. 설정 검증과 같은 규칙이다. */
    public static void requireSidoCode(String sidoCode) {
        if (sidoCode == null || !SIDO_CODE_PATTERN.matcher(sidoCode).matches() || NATION_SIDO_CODE.equals(sidoCode)) {
            throw new IllegalArgumentException("sidoCode must be 2 digits other than 00. sidoCode=" + sidoCode);
        }
    }

    public enum Kind {
        /** {@code /PeriodBasic} 주 단위 — 전국. */
        PERIOD_BASIC_WEEKLY,
        /** {@code /Region} 연 단위 — 시도. */
        REGION_YEARLY
    }
}
