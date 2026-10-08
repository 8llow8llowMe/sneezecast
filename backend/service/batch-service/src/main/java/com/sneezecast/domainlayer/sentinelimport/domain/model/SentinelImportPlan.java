package com.sneezecast.domainlayer.sentinelimport.domain.model;

import com.sneezecast.domainlayer.official.domain.model.KdcaWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 실행 한 번에 보낼 조회 조건 목록. 순서는 고정이다 — 급성호흡기 → 장관감염증 → 인플루엔자(현재 절기) → 인플루엔자(지난 절기).
 *
 * <p>지난 절기는 최근 {@code recentWeeks} 주 창이 지난 절기의 주(35주 이전)에 걸칠 때만 받는다 — 그 주들의 값(잠정 통계)이 아직 바뀔 수
 * 있다. 8주면 36 ~ 42주 실행이 4건이고, 그 밖은 3건이다 (data-api-analysis §3, entity-design §4-1).
 *
 * @param requests 순서대로의 조회 조건
 */
public record SentinelImportPlan(List<SentinelRequest> requests) {

    public SentinelImportPlan {
        requests = List.copyOf(requests);
        Set<String> keys = new HashSet<>();
        for (SentinelRequest request : requests) {
            if (!keys.add(request.requestKey())) {
                throw new IllegalArgumentException("duplicate request. requestKey=" + request.requestKey());
            }
        }
    }

    /**
     * @param runDate     실행일 (원천 주차 기준). 마지막 주는 진행 중인 주다 — 원천이 잠정 통계라 다음 실행이 덮어쓴다
     * @param recentWeeks 병원체별 신고 수를 다시 받을 최근 주 수 (1 이상)
     */
    public static SentinelImportPlan of(LocalDate runDate, int recentWeeks) {
        if (recentWeeks < 1) {
            throw new IllegalArgumentException("recentWeeks must be positive. recentWeeks=" + recentWeeks);
        }
        KdcaWeek to = KdcaWeek.containing(runDate);
        KdcaWeek from = to.minusWeeks(recentWeeks - 1);

        List<SentinelRequest> requests = new ArrayList<>();
        requests.add(SentinelRequest.weeks(SentinelProgram.ARI, from, to));
        requests.add(SentinelRequest.weeks(SentinelProgram.ENTERIC, from, to));
        int seasonStartYear = seasonStartYear(to);
        requests.add(SentinelRequest.season(seasonStartYear));
        if (windowReachesPreviousSeason(to, seasonStartYear, recentWeeks)) {
            requests.add(SentinelRequest.season(seasonStartYear - 1));
        }
        return new SentinelImportPlan(requests);
    }

    public int size() {
        return requests.size();
    }

    /** 현재 주가 든 절기의 시작 연도. 36주 이상이면 그 해가 절기 시작이고, 그보다 앞이면 전년에 시작한 절기 안이다. */
    private static int seasonStartYear(KdcaWeek current) {
        return current.week() >= SentinelRequest.SEASON_START_WEEK ? current.year() : current.year() - 1;
    }

    /**
     * 최근 {@code recentWeeks} 주 창({@code current − (recentWeeks − 1)} ~ {@code current})의 시작이 절기 첫 주(36주)보다 앞인지
     * = {@code current − 36주 < recentWeeks − 1}. 53주가 있는 해 때문에 주차 뺄셈이 아니라 날짜로 센다.
     */
    private static boolean windowReachesPreviousSeason(KdcaWeek current, int seasonStartYear, int recentWeeks) {
        KdcaWeek seasonFirstWeek = new KdcaWeek(seasonStartYear, SentinelRequest.SEASON_START_WEEK);
        return ChronoUnit.WEEKS.between(seasonFirstWeek.start(), current.start()) < recentWeeks - 1;
    }
}
