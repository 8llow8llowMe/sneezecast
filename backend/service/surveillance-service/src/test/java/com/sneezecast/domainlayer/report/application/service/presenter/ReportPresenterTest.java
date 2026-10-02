package com.sneezecast.domainlayer.report.application.service.presenter;

import static org.assertj.core.api.Assertions.assertThat;

import com.sneezecast.common.dto.metadata.CodeNameDescriptionMetadata;
import com.sneezecast.domainlayer.report.adapter.in.web.dto.response.WeeklyReportResponse;
import com.sneezecast.domainlayer.report.application.info.ReportInfo;
import com.sneezecast.domainlayer.report.domain.enums.SymptomGroup;
import com.sneezecast.domainlayer.report.domain.model.ReportWeek;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReportPresenterTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Test
    @DisplayName("저장 시각(KST 지역 시각)을 초 단위 UTC 순간으로 바꾼다 — 소수 초는 버린다")
    void convertsStoredLocalTimeToUtcSeconds() {
        WeeklyReportResponse response = new ReportPresenter(KST).toResponse(info(Set.of()));

        assertThat(response.reportedAt()).isEqualTo(Instant.parse("2026-10-01T05:12:34Z"));
        assertThat(response.updatedAt()).isEqualTo(Instant.parse("2026-10-01T15:00:00Z"));
        assertThat(response.isoWeek()).isEqualTo("2026-W40");
        assertThat(response.districtCode()).isEqualTo("11230510");
        assertThat(response.symptomGroups()).isEmpty();
    }

    @Test
    @DisplayName("증상군은 선언 순서의 {code, name, description} metadata 로 내린다")
    void symptomGroupsAreMetadataInDeclarationOrder() {
        WeeklyReportResponse response = new ReportPresenter(KST).toResponse(info(Set.of(SymptomGroup.ENTERIC, SymptomGroup.RESPIRATORY)));

        assertThat(response.symptomGroups()).containsExactly(
            CodeNameDescriptionMetadata.of("RESPIRATORY", "호흡기", "발열 · 기침 · 인후통"),
            CodeNameDescriptionMetadata.of("ENTERIC", "장관", "구토 · 설사"));
    }

    @Test
    @DisplayName("기본 생성자는 JVM 기본 시간대로 읽는다 — Auditing · 수정 쿼리가 쓰는 기준과 같다")
    void defaultConstructorUsesSystemZone() {
        LocalDateTime storedAt = LocalDateTime.of(2026, 10, 1, 14, 12, 34);

        WeeklyReportResponse response = new ReportPresenter().toResponse(info(Set.of()));

        assertThat(response.reportedAt()).isEqualTo(storedAt.atZone(ZoneId.systemDefault()).toInstant());
    }

    private static ReportInfo info(Set<SymptomGroup> symptomGroups) {
        return ReportInfo.builder()
            .isoWeek(ReportWeek.parse("2026-W40"))
            .districtCode("11230510")
            .symptomGroups(SymptomGroup.fromMask(SymptomGroup.toMask(symptomGroups)))
            .reportedAt(LocalDateTime.of(2026, 10, 1, 14, 12, 34, 987_000_000))
            .updatedAt(LocalDateTime.of(2026, 10, 2, 0, 0))
            .build();
    }
}
