package com.sneezecast.domainlayer.advisory.application.info;

import com.sneezecast.domainlayer.advisory.domain.enums.AdvisoryStatus;
import com.sneezecast.domainlayer.advisory.domain.model.Advisory;
import com.sneezecast.domainlayer.advisory.domain.model.AdvisoryCitation;
import com.sneezecast.domainlayer.report.domain.model.ReportWeek;
import java.time.LocalDateTime;
import lombok.Builder;

/**
 * 운영자가 보는 안내 한 건. {@code version} 은 다음 처리 요청이 실어 보낼 값이다(낙관적 락). 시각은 저장 기준 지역 시각이고 응답 변환(UTC
 * 문자열)은 Presenter(#246)가 한다.
 *
 * @param citation           인용 수치 — 초안 생성 때 고정된 값
 * @param correctsAdvisoryId 정정한 이전 안내. 없으면 null
 * @param createdAt          생성 시각 (검토 시작 시각의 후보)
 */
@Builder
public record AdvisoryInfo(
    long id,
    long aggregateId,
    String districtCode,
    ReportWeek isoWeek,
    AdvisoryStatus status,
    String title,
    String body,
    AdvisoryCitation citation,
    Long correctsAdvisoryId,
    LocalDateTime publishedAt,
    LocalDateTime retractedAt,
    long version,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {

    public static AdvisoryInfo from(Advisory advisory) {
        return AdvisoryInfo.builder()
            .id(advisory.id())
            .aggregateId(advisory.aggregateId())
            .districtCode(advisory.districtCode())
            .isoWeek(advisory.isoWeek())
            .status(advisory.status())
            .title(advisory.title())
            .body(advisory.body())
            .citation(advisory.citation())
            .correctsAdvisoryId(advisory.correctsAdvisoryId())
            .publishedAt(advisory.publishedAt())
            .retractedAt(advisory.retractedAt())
            .version(advisory.version())
            .createdAt(advisory.createdAt())
            .updatedAt(advisory.updatedAt())
            .build();
    }
}
