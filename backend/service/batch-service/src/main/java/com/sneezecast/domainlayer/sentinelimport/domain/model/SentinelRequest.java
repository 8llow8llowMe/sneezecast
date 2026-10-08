package com.sneezecast.domainlayer.sentinelimport.domain.model;

import com.sneezecast.domainlayer.official.domain.model.KdcaWeek;
import java.util.Objects;

/**
 * 표본감시 화면 데이터 조회 조건 하나. 적재 이력({@code official_source_snapshot}) 한 행이 이 단위다 (entity-design §3-3).
 *
 * <p>불변식: 급성호흡기 · 장관감염증은 주 범위({@code from} ~ {@code to}, 연도를 넘어도 된다)로 받고, 인플루엔자는 <b>절기</b>로 받는다
 * (시작 연도 36주 ~ 끝 연도 35주, data-api-analysis §3-3). 섞으면 {@link IllegalArgumentException} 이다.
 *
 * @param program         감시 프로그램
 * @param from            주 범위의 시작. 인플루엔자면 null
 * @param to              주 범위의 끝 ({@code from} 이후이거나 같다). 인플루엔자면 null
 * @param seasonStartYear 절기 시작 연도 (끝 연도는 {@code +1}). 주 범위면 0
 */
public record SentinelRequest(SentinelProgram program, KdcaWeek from, KdcaWeek to, int seasonStartYear) {

    /** {@code request_key} 컬럼 길이 (entity-design §3-3). */
    public static final int MAX_REQUEST_KEY_LENGTH = 200;
    /** 절기의 첫 주. 이 주부터 시작 연도, 다음 해 35주까지가 한 절기다. */
    public static final int SEASON_START_WEEK = 36;

    private static final int MIN_YEAR = 1000;
    private static final int MAX_YEAR = 9999;

    public SentinelRequest {
        Objects.requireNonNull(program, "program must not be null");
        if (program.isPathogenWeekly()) {
            Objects.requireNonNull(from, "from must not be null for weekly request");
            Objects.requireNonNull(to, "to must not be null for weekly request");
            if (from.start().isAfter(to.start())) {
                throw new IllegalArgumentException("from must not be after to. from=%s to=%s".formatted(from, to));
            }
            if (seasonStartYear != 0) {
                throw new IllegalArgumentException("weekly request must not have a season. seasonStartYear=" + seasonStartYear);
            }
        } else {
            if (from != null || to != null) {
                throw new IllegalArgumentException("season request must not have a week range. from=%s to=%s".formatted(from, to));
            }
            if (seasonStartYear < MIN_YEAR || seasonStartYear > MAX_YEAR) {
                throw new IllegalArgumentException("seasonStartYear must be 4 digits. seasonStartYear=" + seasonStartYear);
            }
        }
    }

    /** 병원체별 신고 수 — 주 범위. */
    public static SentinelRequest weeks(SentinelProgram program, KdcaWeek from, KdcaWeek to) {
        return new SentinelRequest(program, from, to, 0);
    }

    /** 인플루엔자 — 절기. */
    public static SentinelRequest season(int seasonStartYear) {
        return new SentinelRequest(SentinelProgram.INFLUENZA_ILI, null, null, seasonStartYear);
    }

    /** 절기 끝 연도 (01 ~ 35주가 이 연도다). 주 범위 요청에는 뜻이 없다. */
    public int seasonEndYear() {
        return seasonStartYear + 1;
    }

    /**
     * 적재 이력의 {@code request_key} ({@value #MAX_REQUEST_KEY_LENGTH}자 이내). 조회 조건만 담는다.
     * 예: {@code sentinel:ari:2026-32~2026-39}, {@code sentinel:influenza:2026-2027}.
     */
    public String requestKey() {
        if (program.isPathogenWeekly()) {
            return "sentinel:%s:%d-%02d~%d-%02d".formatted(program.getKeyName(), from.year(), from.week(), to.year(), to.week());
        }
        return "sentinel:%s:%d-%d".formatted(program.getKeyName(), seasonStartYear, seasonEndYear());
    }
}
