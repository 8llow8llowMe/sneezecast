package com.sneezecast.domainlayer.report.application.info;

import com.sneezecast.domainlayer.report.domain.enums.SymptomGroup;
import com.sneezecast.domainlayer.report.domain.model.ReportWeek;
import com.sneezecast.domainlayer.report.domain.model.WeeklyReport;
import java.time.LocalDateTime;
import java.util.Set;
import lombok.Builder;

/**
 * 본인의 이번 주 보고. <b>보고 ID · 가명 키 · 수정 횟수를 담지 않는다</b> — 응답에 내리지 않는 값은 Presenter 까지 가져가지 않는다.
 *
 * @param reportedAt 이번 주 첫 보고 시각 ({@code created_at}, JVM 기본 시간대의 지역 시각)
 * @param updatedAt  마지막 수정 시각 ({@code updated_at}, 같은 기준)
 */
@Builder
public record ReportInfo(
    ReportWeek isoWeek,
    String districtCode,
    Set<SymptomGroup> symptomGroups,
    LocalDateTime reportedAt,
    LocalDateTime updatedAt
) {

    public static ReportInfo from(WeeklyReport report) {
        return ReportInfo.builder()
            .isoWeek(report.isoWeek())
            .districtCode(report.districtCode())
            .symptomGroups(report.symptoms())
            .reportedAt(report.createdAt())
            .updatedAt(report.updatedAt())
            .build();
    }

    /** 증상(민감정보)을 로그에 흘리지 않는다. */
    @Override
    public String toString() {
        return "ReportInfo[isoWeek=" + (isoWeek == null ? null : isoWeek.value()) + "]";
    }
}
