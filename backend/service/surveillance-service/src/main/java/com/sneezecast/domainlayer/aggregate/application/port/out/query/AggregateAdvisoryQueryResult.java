package com.sneezecast.domainlayer.aggregate.application.port.out.query;

import com.sneezecast.domainlayer.report.domain.model.ReportWeek;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 동네 · 주 하나에 발행 중인 안내 요약 — 홈 안내 섹션 · 지도 {@code 이 동네 안내 보기} 가 쓰는 값만 옮긴다. 본문 · 인용 수치는 동네 안내 API 가 준다.
 *
 * @param districtCode 행정동 코드
 * @param isoWeek      안내의 근거 집계 주
 * @param title        제목
 * @param publishedAt  발행 시각 (저장 기준 지역 시각)
 */
public record AggregateAdvisoryQueryResult(
    String districtCode,
    ReportWeek isoWeek,
    String title,
    LocalDateTime publishedAt
) {

    public AggregateAdvisoryQueryResult {
        Objects.requireNonNull(districtCode, "districtCode");
        Objects.requireNonNull(isoWeek, "isoWeek");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(publishedAt, "publishedAt");
    }
}
