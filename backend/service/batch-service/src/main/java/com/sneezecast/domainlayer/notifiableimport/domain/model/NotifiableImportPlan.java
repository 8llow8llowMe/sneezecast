package com.sneezecast.domainlayer.notifiableimport.domain.model;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 실행 한 번에 보낼 조회 조건 목록. 순서는 고정이다 — 주별 전국(올해 → 전년) → 시도 연별(올해: 발생 수 시도 순 → 10만 명당 시도 순)
 * → 시도 연별(전년: 같은 순).
 *
 * <p>화면이 먼저 쓰는 주별 전국을 앞에 둬서, 호출 상한이나 원천 장애로 중간에 멈춰도 가장 중요한 값부터 남는다. 시도 18개면
 * 2 + 2 × 2 × 18 = 74건이다 (data-api-analysis §2-5).
 *
 * @param requests 순서대로의 조회 조건
 */
public record NotifiableImportPlan(List<NotifiableRequest> requests) {

    public NotifiableImportPlan {
        requests = List.copyOf(requests);
        Set<String> keys = new HashSet<>();
        for (NotifiableRequest request : requests) {
            if (!keys.add(request.requestKey())) {
                throw new IllegalArgumentException("duplicate request. requestKey=" + request.requestKey());
            }
        }
    }

    /**
     * @param currentYear 올해 (실행 시각 기준). 전년은 {@code currentYear - 1}
     * @param sidoCodes   질병관리청 시도 코드 — 이 순서대로 부른다
     */
    public static NotifiableImportPlan of(int currentYear, List<String> sidoCodes) {
        int[] years = {currentYear, currentYear - 1};
        List<NotifiableRequest> requests = new ArrayList<>();
        for (int year : years) {
            requests.add(NotifiableRequest.weekly(year));
        }
        for (int year : years) {
            for (NotifiableRegionMeasure measure : NotifiableRegionMeasure.values()) {
                for (String sidoCode : sidoCodes) {
                    requests.add(NotifiableRequest.region(year, measure, sidoCode));
                }
            }
        }
        return new NotifiableImportPlan(requests);
    }

    public int size() {
        return requests.size();
    }
}
