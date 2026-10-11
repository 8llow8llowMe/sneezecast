package com.sneezecast.domainlayer.advisory.application.info;

import com.sneezecast.domainlayer.report.domain.model.ReportWeek;
import java.util.List;
import java.util.Objects;

/**
 * 운영자 발행 이력(#267, A03) — {@code fromWeek} ~ {@code toWeek}(4주, 양 끝 포함)의 발행됨 · 철회됨 안내, 발행 시각 최근 것부터.
 *
 * @param fromWeek 범위의 첫 주 ({@code toWeek} − 3주)
 * @param toWeek   범위의 끝 주 (요청한 주, 생략하면 현재 주 − 1)
 * @param entries  안내마다 한 항목
 */
public record PublicationHistoryListInfo(
    ReportWeek fromWeek,
    ReportWeek toWeek,
    List<Entry> entries
) {

    public PublicationHistoryListInfo {
        Objects.requireNonNull(fromWeek, "fromWeek");
        Objects.requireNonNull(toWeek, "toWeek");
        entries = List.copyOf(entries);
    }

    /**
     * 안내 한 건과 그 둘레.
     *
     * @param advisory              안내 (인용 수치 · {@code correctsAdvisoryId} 포함)
     * @param districtName          행정동 이름. 행정동 행이 없으면 null
     * @param sigungu               같은 이름의 동을 가르는 시도 · 시군구 표기. 행정동 행이 없으면 null
     * @param correctedByAdvisoryId 이 안내를 정정한 새 안내 — 조회 때 같은 목록의 {@code correctsAdvisoryId} 를 거꾸로 이어 만든다. 정정되지 않았으면 null
     * @param histories             전 이력, 오래된 것부터
     */
    public record Entry(
        AdvisoryInfo advisory,
        String districtName,
        String sigungu,
        Long correctedByAdvisoryId,
        List<AdvisoryHistoryInfo> histories
    ) {

        public Entry {
            Objects.requireNonNull(advisory, "advisory");
            histories = List.copyOf(histories);
        }
    }
}
