package com.sneezecast.domainlayer.advisory.application.info;

import com.sneezecast.domainlayer.advisory.domain.enums.AdvisoryStatus;
import com.sneezecast.domainlayer.advisory.domain.enums.ReviewSignal;
import com.sneezecast.domainlayer.aggregate.domain.enums.AggregateLevel;
import com.sneezecast.domainlayer.aggregate.domain.enums.InsufficientReason;
import com.sneezecast.domainlayer.report.domain.enums.SymptomGroup;
import com.sneezecast.domainlayer.report.domain.model.ReportWeek;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import lombok.Builder;

/**
 * 한 주의 예방 안내 검토 후보 (#266). 익명 집계와 안내 상태만 담는다 — 보고자 · 운영자 정보가 없다.
 *
 * @param isoWeek              조회한 주
 * @param finalized            그 주에 마감된 집계 행이 있는지 (마감은 주 단위 벌크라 이 값이 그 주의 마감 여부다)
 * @param surgeChangePercent   지금 쓰는 참여 급증 기준 (%)
 * @param repeatRevisedPercent 지금 쓰는 반복 보고 기준 (%)
 * @param candidates           후보. 정렬은 {@code AdvisoryReviewCandidateQueryProcessor} 가 정한다
 */
public record ReviewCandidateListInfo(
    ReportWeek isoWeek,
    boolean finalized,
    int surgeChangePercent,
    int repeatRevisedPercent,
    List<Candidate> candidates
) {

    public ReviewCandidateListInfo {
        Objects.requireNonNull(isoWeek, "isoWeek");
        candidates = List.copyOf(candidates);
    }

    /**
     * 동네 하나의 후보.
     *
     * <p><b>불변식: 자료 부족(INSUFFICIENT)이면 {@code measured} 가 없고 {@code draftable} 이 false 다</b> — 증상 수 · 비율 · 기준선을 담지 않는다(루트
     * CLAUDE.md "자료 부족이면 수치 · 색으로 위험을 암시하지 않는다"). 참여 · 전주 참여 · 수정 수는 늘 담는다 — 증상 정보가 아니고 급증 · 반복 신호의 근거다.
     *
     * @param districtName             행정동 이름. 행정동 행이 없으면 null
     * @param sigungu                  시도 · 시군구 표기. 행정동 행이 없으면 null
     * @param active                   현행이면 true. 폐지됐거나 행정동 행이 없으면 false
     * @param signals                  신호, 선언 순서(기준선 → 급증 → 반복). 신호 없이 그 주에 안내가 있어 후보가 되면 빈 목록
     * @param draftable                이 칸으로 초안을 만들 수 있는지 — 마감됐고 자료 부족이 아니다(초안 생성 전제, entity-design §2-3)
     * @param previousParticipantCount 전주 참여자 수. 전주 행이 없으면 null
     * @param measured                 수치. 자료 부족이면 null
     * @param advisory                 그 동네 · 주의 안내 상태. 없으면 null
     */
    @Builder
    public record Candidate(
        String districtCode,
        String districtName,
        String sigungu,
        boolean active,
        List<ReviewSignal> signals,
        AggregateLevel level,
        InsufficientReason insufficientReason,
        boolean draftable,
        int participantCount,
        Integer previousParticipantCount,
        int revisedReportCount,
        Measured measured,
        AdvisoryState advisory
    ) {

        public Candidate {
            Objects.requireNonNull(districtCode, "districtCode");
            Objects.requireNonNull(level, "level");
            signals = List.copyOf(signals);
            boolean insufficient = level == AggregateLevel.INSUFFICIENT;
            if (insufficient != (measured == null) || insufficient != (insufficientReason != null) || (insufficient && draftable)) {
                throw new IllegalArgumentException("자료 부족이면 수치 없이 이유만 있고 초안을 만들 수 없어야 합니다. code=" + districtCode + ", level=" + level);
            }
        }
    }

    /**
     * 수치가 있는 단계의 값. 비율은 퍼센트 소수 첫째 자리 반올림이다({@code AggregateRate}).
     *
     * @param baselineRate    판정에 쓴 기준선 비율 (%) — 집계 행의 {@code baseline_*} 로 계산한다
     * @param baselineDeltaPp {@code symptomRate − baselineRate} (%p). 화면에 보이는 두 비율과 맞도록 반올림한 값끼리 뺀다
     * @param groups          증상군별 보고 수 · 비율, 선언 순서(호흡기 → 장관)
     */
    public record Measured(
        int symptomaticCount,
        BigDecimal symptomRate,
        BigDecimal baselineRate,
        BigDecimal baselineDeltaPp,
        List<GroupCount> groups
    ) {

        public Measured {
            Objects.requireNonNull(symptomRate, "symptomRate");
            Objects.requireNonNull(baselineRate, "baselineRate");
            Objects.requireNonNull(baselineDeltaPp, "baselineDeltaPp");
            groups = List.copyOf(groups);
        }
    }

    public record GroupCount(
        SymptomGroup group,
        int count,
        BigDecimal rate
    ) {

    }

    /**
     * 후보 동네 · 주의 안내 하나 — 처리 요청(상세 · 수정 · 승인 · 발행)이 쓸 ID · 상태 · 버전.
     */
    public record AdvisoryState(
        long id,
        AdvisoryStatus status,
        long version
    ) {

        public AdvisoryState {
            Objects.requireNonNull(status, "status");
        }
    }
}
