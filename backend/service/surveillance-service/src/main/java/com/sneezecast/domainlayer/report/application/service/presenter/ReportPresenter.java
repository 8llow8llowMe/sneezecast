package com.sneezecast.domainlayer.report.application.service.presenter;

import com.sneezecast.domainlayer.report.adapter.in.web.dto.response.WeeklyReportResponse;
import com.sneezecast.domainlayer.report.application.info.ReportInfo;
import com.sneezecast.domainlayer.report.domain.enums.SymptomGroup;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Component;

/**
 * 보고 Info → 응답 DTO. 시각은 coding-conventions §2-2 대로 초 단위 UTC {@link Instant} 로, 증상군은 §7 metadata 객체로 바꾼다.
 *
 * <p>저장 시각({@code created_at} · {@code updated_at})은 Auditing 과 수정 쿼리가 <b>JVM 기본 시간대의 지역 시각</b>으로 쓴다 (배포 환경은
 * {@code -Duser.timezone=Asia/Seoul}). 그래서 같은 기준으로 읽어 순간으로 되돌린다 — 다른 시간대로 해석하면 시각이 그 차이만큼 밀린다.
 */
@Component
public class ReportPresenter {

    private final ZoneId storageZone;

    public ReportPresenter() {
        this(ZoneId.systemDefault());
    }

    /** 테스트가 JVM 시간대와 무관하게 변환을 고정할 때 쓴다. */
    ReportPresenter(ZoneId storageZone) {
        this.storageZone = storageZone;
    }

    public WeeklyReportResponse toResponse(ReportInfo report) {
        return WeeklyReportResponse.builder()
            .isoWeek(report.isoWeek().value())
            .districtCode(report.districtCode())
            .symptomGroups(report.symptomGroups().stream().map(SymptomGroup::toMetadata).toList())
            .reportedAt(toInstant(report.reportedAt()))
            .updatedAt(toInstant(report.updatedAt()))
            .build();
    }

    private Instant toInstant(LocalDateTime storedAt) {
        return storedAt.atZone(storageZone).toInstant().truncatedTo(ChronoUnit.SECONDS);
    }
}
