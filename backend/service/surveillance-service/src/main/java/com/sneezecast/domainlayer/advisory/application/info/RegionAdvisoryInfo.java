package com.sneezecast.domainlayer.advisory.application.info;

import com.sneezecast.domainlayer.aggregate.domain.enums.AggregateLevel;
import com.sneezecast.domainlayer.report.domain.enums.SymptomGroup;
import com.sneezecast.domainlayer.report.domain.model.ReportWeek;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 공개 동네 안내 — 한 동네 · 한 주. <b>발행 중인 안내({@code published}) 또는 철회 표시({@code retractedAt}) 중 하나만</b> 있다. 둘 다 없으면 Info 를
 * 만들지 않는다(안내 없음).
 *
 * <p>운영자 · 이력 내부 정보(안내 ID, 운영자 ID, 버전, 근거 집계 ID, 규칙 버전, AI 모델, 메모)를 담지 않는다 — 공개 응답으로 옮길 자리부터 두지 않는다.
 * 철회된 안내의 제목 · 본문 · 인용 수치도 담지 않는다(내린 안내를 다시 퍼뜨리지 않게).
 *
 * @param districtCode 행정동 코드
 * @param isoWeek      근거 집계 주
 * @param published    발행 중인 안내. 철회 표시면 null
 * @param retractedAt  철회 시각 (저장 기준 지역 시각). 발행 중인 안내가 있으면 null
 */
public record RegionAdvisoryInfo(
    String districtCode,
    ReportWeek isoWeek,
    Published published,
    LocalDateTime retractedAt
) {

    public RegionAdvisoryInfo {
        Objects.requireNonNull(districtCode, "districtCode");
        Objects.requireNonNull(isoWeek, "isoWeek");
        if ((published == null) == (retractedAt == null)) {
            throw new IllegalArgumentException("발행 중인 안내와 철회 표시 중 하나만 있어야 합니다. districtCode=" + districtCode + ", isoWeek=" + isoWeek);
        }
    }

    public static RegionAdvisoryInfo published(String districtCode, ReportWeek isoWeek, Published published) {
        return new RegionAdvisoryInfo(districtCode, isoWeek, Objects.requireNonNull(published, "published"), null);
    }

    public static RegionAdvisoryInfo retracted(String districtCode, ReportWeek isoWeek, LocalDateTime retractedAt) {
        return new RegionAdvisoryInfo(districtCode, isoWeek, null, Objects.requireNonNull(retractedAt, "retractedAt"));
    }

    /**
     * 발행 중인 안내. 수치는 운영자가 검토할 때 인용한 값(발행 시점 고정, {@code cited_*})이고 비율은 그 수로 계산한다 — 집계가 다시 계산돼도 바뀌지 않는다.
     *
     * @param title            제목
     * @param body             본문
     * @param publishedAt      발행 시각 (저장 기준 지역 시각)
     * @param level            인용 단계 (NORMAL · SLIGHT · HIGH — 자료 부족은 인용할 수 없다)
     * @param participantCount 인용 참여자 수
     * @param symptomaticCount 인용 증상 보고 수
     * @param symptomRate      인용 수로 계산한 증상 보고 비율 (%)
     * @param groups           증상군별 인용 수 · 비율. 선언 순서(호흡기 → 장관)
     * @param correctedAts     정정 시각 — 이 안내가 {@code corrects_advisory_id} 로 이은 이전 안내마다 하나, 최근 것부터. 정정이 없으면 빈 목록
     */
    public record Published(
        String title,
        String body,
        LocalDateTime publishedAt,
        AggregateLevel level,
        int participantCount,
        int symptomaticCount,
        BigDecimal symptomRate,
        List<CitedGroup> groups,
        List<LocalDateTime> correctedAts
    ) {

        public Published {
            Objects.requireNonNull(publishedAt, "publishedAt");
            Objects.requireNonNull(level, "level");
            Objects.requireNonNull(symptomRate, "symptomRate");
            groups = List.copyOf(groups);
            correctedAts = List.copyOf(correctedAts);
        }
    }

    /**
     * @param group 증상군
     * @param count 인용 보고 수
     * @param rate  인용 참여자 대비 그 증상군 보고 비율 (%)
     */
    public record CitedGroup(
        SymptomGroup group,
        int count,
        BigDecimal rate
    ) {

        public CitedGroup {
            Objects.requireNonNull(group, "group");
            Objects.requireNonNull(rate, "rate");
        }
    }
}
